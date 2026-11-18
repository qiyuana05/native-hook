package com.changan.nativehook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AppListActivity extends Activity {

    private DmClient dm;
    private ListView lvApps;
    private EditText etSearch;
    private CheckBox cbSystem;
    private TextView status;
    private Button btnRefresh;
    private AppAdapter adapter;

    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private final List<AppEntry> shownApps = new ArrayList<AppEntry>();
    private boolean busy;

    private static class AppEntry {
        final String label;
        final String pkg;
        final boolean system;

        AppEntry(String label, String pkg, boolean system) {
            this.label = label;
            this.pkg = pkg;
            this.system = system;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dm = new DmClient(this);
        buildUi();
        loadApps();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, UiSafeArea.TOP_INSET_PX, p, p);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("应用列表");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button back = new Button(this);
        back.setText("返回");
        back.setAllCaps(false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        status = new TextView(this);
        status.setText("点击应用即可卸载；请先在主界面启动 Hook。");
        status.setTextSize(13);
        root.addView(status);

        etSearch = new EditText(this);
        etSearch.setHint("搜索应用名或包名");
        etSearch.setSingleLine(true);
        root.addView(etSearch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        cbSystem = new CheckBox(this);
        cbSystem.setText("显示系统应用");
        cbSystem.setChecked(false);
        root.addView(cbSystem);

        btnRefresh = new Button(this);
        btnRefresh.setText("刷新列表");
        btnRefresh.setAllCaps(false);
        root.addView(btnRefresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        lvApps = new ListView(this);
        adapter = new AppAdapter();
        lvApps.setAdapter(adapter);
        lvApps.setDividerHeight(dp(1));
        root.addView(lvApps, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        cbSystem.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                applyFilter();
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadApps();
            }
        });
        lvApps.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                if (position >= 0 && position < shownApps.size()) {
                    confirmUninstall(shownApps.get(position));
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> infos = pm.getInstalledApplications(0);
        allApps.clear();
        for (ApplicationInfo ai : infos) {
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            String label;
            try {
                label = pm.getApplicationLabel(ai).toString();
            } catch (Exception e) {
                label = ai.packageName;
            }
            allApps.add(new AppEntry(label, ai.packageName, system));
        }
        Collections.sort(allApps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        applyFilter();
    }

    private void applyFilter() {
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.US);
        boolean showSystem = cbSystem.isChecked();
        shownApps.clear();
        for (AppEntry e : allApps) {
            if (!showSystem && e.system) {
                continue;
            }
            if (q.length() > 0) {
                String label = e.label.toLowerCase(Locale.US);
                String pkg = e.pkg.toLowerCase(Locale.US);
                if (!label.contains(q) && !pkg.contains(q)) {
                    continue;
                }
            }
            shownApps.add(e);
        }
        adapter.notifyDataSetChanged();
        status.setText("共 " + shownApps.size() + " 个应用；点击应用即可卸载。");
    }

    private void confirmUninstall(final AppEntry e) {
        String msg = e.pkg + "\n\n"
                + (e.system ? "这是系统应用，卸载后可能影响车机功能。\n\n" : "")
                + (e.pkg.equals(getPackageName())
                ? "这是本工具自身；卸载后 hook 仍在运行，重启后消失。\n\n" : "")
                + "确认 Hook 已启动。";
        new AlertDialog.Builder(this)
                .setTitle("卸载 " + e.label)
                .setMessage(msg)
                .setPositiveButton("卸载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doUninstall(e);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doUninstall(final AppEntry e) {
        setBusy(true);
        status.setText("正在卸载 " + e.label + " ...");
        dm.exec("pm uninstall --user 0 " + e.pkg + "\n", new DmClient.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                boolean success = ok && out != null && out.contains("Success");
                status.setText(success ? ("已卸载 " + e.label) : ("卸载失败：" + e.label));
                if (!success) {
                    new AlertDialog.Builder(AppListActivity.this)
                            .setTitle("卸载失败")
                            .setMessage(out == null ? "无输出" : out)
                            .setPositiveButton("确定", null)
                            .show();
                }
                setBusy(false);
                loadApps();
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        btnRefresh.setEnabled(!b);
        etSearch.setEnabled(!b);
        cbSystem.setEnabled(!b);
        lvApps.setEnabled(!b);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shownApps.size();
        }

        @Override
        public Object getItem(int position) {
            return shownApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry e = shownApps.get(position);
            LinearLayout row = new LinearLayout(AppListActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setMinimumHeight(dp(64));
            row.setBackgroundResource(android.R.drawable.list_selector_background);

            TextView label = new TextView(AppListActivity.this);
            label.setText(e.label + (e.system ? "  [系统]" : ""));
            label.setTextSize(18);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label);

            TextView pkg = new TextView(AppListActivity.this);
            pkg.setText(e.pkg);
            pkg.setTextSize(12);
            pkg.setTextColor(Color.rgb(0x66, 0x66, 0x66));
            pkg.setSingleLine(true);
            pkg.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(pkg);

            return row;
        }
    }
}