"""
VoIP Signaling Server
Протокол: WebSocket + UDP (медиа)
"""
import asyncio
import json
import logging
import random
import time
import socket
import struct
import threading
from typing import Dict, Optional
import websockets
from websockets.server import WebSocketServerProtocol

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s'
)
log = logging.getLogger(__name__)

# ─── Хранилище пользователей ────────────────────────────────────────────────
class UserRegistry:
    def __init__(self):
        self.users: Dict[str, dict] = {}   # number -> {ws, ip, udp_port, last_seen}
        self.ws_to_number: Dict[str, str] = {}  # ws_id -> number
        self.active_calls: Dict[str, dict] = {} # call_id -> {caller, callee, state}

    def register(self, number: str, ws: WebSocketServerProtocol, ip: str, udp_port: int):
        self.users[number] = {
            "ws": ws,
            "ip": ip,
            "udp_port": udp_port,
            "last_seen": time.time(),
            "status": "online"
        }
        self.ws_to_number[id(ws)] = number
        log.info(f"Registered: {number} from {ip}:{udp_port}")

    def unregister(self, ws: WebSocketServerProtocol):
        number = self.ws_to_number.pop(id(ws), None)
        if number and number in self.users:
            del self.users[number]
            log.info(f"Unregistered: {number}")
        return number

    def get_user(self, number: str) -> Optional[dict]:
        return self.users.get(number)

    def get_number_by_ws(self, ws: WebSocketServerProtocol) -> Optional[str]:
        return self.ws_to_number.get(id(ws))

    def generate_number(self) -> str:
        """Генерация уникального 4-значного номера"""
        while True:
            num = str(random.randint(1000, 9999))
            if num not in self.users:
                return num

    def create_call(self, caller: str, callee: str) -> str:
        call_id = f"{caller}-{callee}-{int(time.time())}"
        self.active_calls[call_id] = {
            "caller": caller,
            "callee": callee,
            "state": "ringing",
            "started": time.time()
        }
        return call_id

    def end_call(self, call_id: str):
        self.active_calls.pop(call_id, None)


registry = UserRegistry()


# ─── WebSocket обработчик ────────────────────────────────────────────────────
async def handle_client(ws: WebSocketServerProtocol):
    client_ip = ws.remote_address[0]
    log.info(f"New connection from {client_ip}")

    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
                await route_message(ws, client_ip, msg)
            except json.JSONDecodeError:
                await send(ws, {"type": "error", "message": "Invalid JSON"})
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        number = registry.unregister(ws)
        if number:
            # Завершить активные звонки
            for call_id, call in list(registry.active_calls.items()):
                if call["caller"] == number or call["callee"] == number:
                    other = call["callee"] if call["caller"] == number else call["caller"]
                    other_user = registry.get_user(other)
                    if other_user:
                        await send(other_user["ws"], {
                            "type": "call_ended",
                            "call_id": call_id,
                            "reason": "peer_disconnected"
                        })
                    registry.end_call(call_id)


async def route_message(ws: WebSocketServerProtocol, client_ip: str, msg: dict):
    mtype = msg.get("type")

    if mtype == "register":
        await handle_register(ws, client_ip, msg)

    elif mtype == "call":
        await handle_call(ws, msg)

    elif mtype == "call_answer":
        await handle_answer(ws, msg)

    elif mtype == "call_reject":
        await handle_reject(ws, msg)

    elif mtype == "call_end":
        await handle_end(ws, msg)

    elif mtype == "ice_candidate":
        await handle_ice(ws, msg)

    elif mtype == "keepalive":
        number = registry.get_number_by_ws(ws)
        if number and number in registry.users:
            registry.users[number]["last_seen"] = time.time()
        await send(ws, {"type": "pong"})

    elif mtype == "get_users":
        await handle_get_users(ws)

    else:
        await send(ws, {"type": "error", "message": f"Unknown type: {mtype}"})


async def handle_register(ws, client_ip, msg):
    udp_port = msg.get("udp_port", 5004)
    requested_number = msg.get("number")  # переподключение

    if requested_number and requested_number in registry.users:
        # Номер уже занят - дать новый
        requested_number = None

    number = requested_number or registry.generate_number()
    registry.register(number, ws, client_ip, udp_port)

    await send(ws, {
        "type": "registered",
        "number": number,
        "message": f"Вы зарегистрированы как {number}"
    })


async def handle_call(ws, msg):
    caller_number = registry.get_number_by_ws(ws)
    callee_number = msg.get("to")

    if not caller_number:
        await send(ws, {"type": "error", "message": "Не зарегистрированы"})
        return

    callee = registry.get_user(callee_number)
    if not callee:
        await send(ws, {"type": "call_failed", "reason": "User not found"})
        return

    call_id = registry.create_call(caller_number, callee_number)
    caller = registry.get_user(caller_number)

    # Уведомить вызываемого
    await send(callee["ws"], {
        "type": "incoming_call",
        "call_id": call_id,
        "from": caller_number,
        "caller_ip": caller["ip"],
        "caller_udp_port": caller["udp_port"]
    })

    # Подтвердить вызывающему
    await send(ws, {
        "type": "call_ringing",
        "call_id": call_id,
        "to": callee_number
    })

    log.info(f"Call: {caller_number} -> {callee_number}, id={call_id}")


async def handle_answer(ws, msg):
    call_id = msg.get("call_id")
    call = registry.active_calls.get(call_id)
    if not call:
        await send(ws, {"type": "error", "message": "Звонок не найден"})
        return

    call["state"] = "active"
    callee_number = registry.get_number_by_ws(ws)
    callee = registry.get_user(callee_number)
    caller = registry.get_user(call["caller"])

    if caller:
        await send(caller["ws"], {
            "type": "call_accepted",
            "call_id": call_id,
            "callee_ip": callee["ip"],
            "callee_udp_port": callee["udp_port"]
        })

    log.info(f"Call accepted: {call_id}")


async def handle_reject(ws, msg):
    call_id = msg.get("call_id")
    call = registry.active_calls.get(call_id)
    if not call:
        return

    caller = registry.get_user(call["caller"])
    if caller:
        await send(caller["ws"], {
            "type": "call_rejected",
            "call_id": call_id
        })

    registry.end_call(call_id)
    log.info(f"Call rejected: {call_id}")


async def handle_end(ws, msg):
    call_id = msg.get("call_id")
    call = registry.active_calls.get(call_id)
    if not call:
        return

    number = registry.get_number_by_ws(ws)
    other_number = call["callee"] if call["caller"] == number else call["caller"]
    other = registry.get_user(other_number)

    if other:
        await send(other["ws"], {
            "type": "call_ended",
            "call_id": call_id,
            "reason": "hangup"
        })

    registry.end_call(call_id)
    log.info(f"Call ended: {call_id}")


async def handle_ice(ws, msg):
    """Проброс ICE кандидатов для WebRTC"""
    call_id = msg.get("call_id")
    call = registry.active_calls.get(call_id)
    if not call:
        return

    number = registry.get_number_by_ws(ws)
    other_number = call["callee"] if call["caller"] == number else call["caller"]
    other = registry.get_user(other_number)

    if other:
        await send(other["ws"], {
            "type": "ice_candidate",
            "call_id": call_id,
            "candidate": msg.get("candidate")
        })


async def handle_get_users(ws):
    number = registry.get_number_by_ws(ws)
    users = [
        {"number": n, "status": d["status"]}
        for n, d in registry.users.items()
        if n != number
    ]
    await send(ws, {"type": "users_list", "users": users})


async def send(ws: WebSocketServerProtocol, data: dict):
    try:
        await ws.send(json.dumps(data))
    except Exception as e:
        log.warning(f"Send failed: {e}")


# ─── Keepalive очиститель ────────────────────────────────────────────────────
async def cleanup_task():
    while True:
        await asyncio.sleep(30)
        now = time.time()
        stale = [
            n for n, d in registry.users.items()
            if now - d["last_seen"] > 120  # 2 минуты
        ]
        for n in stale:
            log.info(f"Stale user removed: {n}")
            del registry.users[n]


# ─── Запуск ──────────────────────────────────────────────────────────────────
async def main():
    host = "0.0.0.0"
    port = 8765

    log.info(f"VoIP Server starting on ws://{host}:{port}")

    async with websockets.serve(handle_client, host, port, ping_interval=20, ping_timeout=60):
        asyncio.create_task(cleanup_task())
        log.info("Server ready. Waiting for connections...")
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    asyncio.run(main())
