package com.voip.app.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.voip.app.R;
import com.voip.app.model.CallRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CallHistoryActivity extends AppCompatActivity {

    private RecyclerView rvCallHistory;
    private List<CallRecord> callRecords = new ArrayList<>();
    private CallHistoryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_history);

        rvCallHistory = findViewById(R.id.rvCallHistory);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnClear = findViewById(R.id.btnClearHistory);

        adapter = new CallHistoryAdapter(callRecords);
        rvCallHistory.setLayoutManager(new LinearLayoutManager(this));
        rvCallHistory.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());
        btnClear.setOnClickListener(v -> {
            clearHistory();
            callRecords.clear();
            adapter.notifyDataSetChanged();
            Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show();
        });

        loadHistory();
    }

    private void loadHistory() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("voip_prefs", Context.MODE_PRIVATE);
            String json = prefs.getString("call_history", "[]");
            JSONArray arr = new JSONArray(json);
            callRecords.clear();
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                CallRecord r = new CallRecord(
                    o.getString("type"),
                    o.getString("number"),
                    o.optString("displayName", o.getString("number")),
                    o.getLong("timestamp"),
                    o.getInt("durationSeconds")
                );
                callRecords.add(r);
            }
            adapter.notifyDataSetChanged();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clearHistory() {
        getSharedPreferences("voip_prefs", Context.MODE_PRIVATE)
            .edit().remove("call_history").apply();
    }

    public static void addRecord(Context ctx, CallRecord record) {
        try {
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("voip_prefs", Context.MODE_PRIVATE);
            String json = prefs.getString("call_history", "[]");
            JSONArray arr = new JSONArray(json);
            JSONObject o = new JSONObject();
            o.put("type", record.type);
            o.put("number", record.number);
            o.put("displayName", record.displayName != null ? record.displayName : record.number);
            o.put("timestamp", record.timestamp);
            o.put("durationSeconds", record.durationSeconds);
            arr.put(o);
            // Храним не более 100 записей
            while (arr.length() > 100) arr.remove(0);
            prefs.edit().putString("call_history", arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── Adapter ─────────────────────────────────────────────────────────────

    static class CallHistoryAdapter extends RecyclerView.Adapter<CallHistoryAdapter.VH> {

        private final List<CallRecord> items;
        private final SimpleDateFormat sdf = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());

        CallHistoryAdapter(List<CallRecord> items) { this.items = items; }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_call_record, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            CallRecord r = items.get(pos);

            // Иконка типа звонка
            switch (r.type) {
                case CallRecord.TYPE_INCOMING:  h.icon.setText("📞"); h.icon.setTextColor(0xFF27ae60); break;
                case CallRecord.TYPE_OUTGOING:  h.icon.setText("📲"); h.icon.setTextColor(0xFF4a90d9); break;
                case CallRecord.TYPE_MISSED:    h.icon.setText("📵"); h.icon.setTextColor(0xFFe74c3c); break;
                default: h.icon.setText("📞"); break;
            }

            String name = (r.displayName != null && !r.displayName.isEmpty()) ? r.displayName : r.number;
            h.name.setText(name);
            h.number.setText(r.number);
            h.time.setText(sdf.format(new Date(r.timestamp)));

            if (r.durationSeconds > 0) {
                int m = r.durationSeconds / 60;
                int s = r.durationSeconds % 60;
                h.duration.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
            } else {
                h.duration.setText(r.type.equals(CallRecord.TYPE_MISSED) ? "Пропущен" : "");
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView icon, name, number, time, duration;
            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.tvCallIcon);
                name = v.findViewById(R.id.tvCallerName);
                number = v.findViewById(R.id.tvCallerNumber);
                time = v.findViewById(R.id.tvCallTime);
                duration = v.findViewById(R.id.tvCallDuration);
            }
        }
    }
}
