package com.cinelog.app;

import android.app.*;
import android.os.Bundle;
import android.os.Build;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16,20,17), SURFACE = Color.rgb(23,29,25), SURFACE2 = Color.rgb(31,40,33);
    private static final int LINE = Color.rgb(47,57,49), TEXT = Color.rgb(242,244,239), MUTED = Color.rgb(151,162,151), LIME = Color.rgb(198,243,107);
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<Tag> tags = new ArrayList<>();
    private android.content.SharedPreferences prefs;
    private LocalDatabase localDb;
    private LinearLayout root, content, nav;
    private ScrollView scroll;
    private EditText searchField;
    private int page = 0;
    private String query = "";
    private String apiToken = "";
    private String username = "";
    private String apiBaseUrl = "";
    private final java.util.concurrent.ExecutorService network = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        prefs = getSharedPreferences("cinelog", MODE_PRIVATE); localDb=new LocalDatabase(this); apiToken=prefs.getString("api_token",""); username=prefs.getString("username",""); apiBaseUrl=defaultApiUrl(); load();
        if(apiToken.isEmpty()) showAuthScreen(); else {buildShell();render();loadFromServer();}
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private String defaultApiUrl(){boolean emulator=Build.FINGERPRINT.startsWith("generic")||Build.MODEL.toLowerCase(Locale.ROOT).contains("emulator")||Build.MODEL.contains("模拟器");return emulator?"http://10.0.2.2:8000":BuildConfig.API_BASE_URL;}
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if(stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private TextView label(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams lp(int w,int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private void load() {
        if(localDb.getState("legacy_imported").isEmpty()) try {
            JSONArray a = new JSONArray(prefs.getString("entries", "[]"));
            for(int i=0;i<a.length();i++) entries.add(Entry.from(a.getJSONObject(i)));
            JSONArray ts = new JSONArray(prefs.getString("tags", "[]"));
            if(ts.length()==0 && prefs.getString("tags", null)==null) { tags.add(new Tag("值得重看",true,true)); tags.add(new Tag("科幻",true,true)); tags.add(new Tag("悬疑",true,true)); }
            for(int i=0;i<ts.length();i++) {
                Object item=ts.get(i);
                if(item instanceof JSONObject) {JSONObject o=(JSONObject)item; tags.add(new Tag(o.optString("name"),o.optBoolean("movie",true),o.optBoolean("series",true)));}
                else tags.add(new Tag(String.valueOf(item),true,true));
            }
            localDb.saveLibrary(entries,tags);localDb.putState("legacy_imported","yes");
        } catch(Exception ignored) { }
        else localDb.loadLibrary(entries,tags);
    }
    private void save() {
        try {
            storeLocal();
            if(!apiToken.isEmpty()) syncToServer();
        } catch(Exception ignored) { }
    }
    private JSONObject request(String method,String path,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(apiBaseUrl+path).openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setRequestProperty("Accept","application/json");
        if(!apiToken.isEmpty())c.setRequestProperty("Authorization","Bearer "+apiToken);
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}}
        int code=c.getResponseCode();java.io.InputStream input=code<400?c.getInputStream():c.getErrorStream();StringBuilder text=new StringBuilder();
        if(input!=null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,"UTF-8"))){String line;while((line=reader.readLine())!=null)text.append(line);}
        c.disconnect();if(code<200||code>=300)throw new Exception(text.length()>0?text.toString():"HTTP "+code);
        return text.length()==0?new JSONObject():new JSONObject(text.toString());
    }
    private JSONArray requestArray(String path) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(apiBaseUrl+path).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setRequestProperty("Authorization","Bearer "+apiToken);
        int code=c.getResponseCode();java.io.InputStream input=code<400?c.getInputStream():c.getErrorStream();StringBuilder text=new StringBuilder();if(input!=null)try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,"UTF-8"))){String line;while((line=reader.readLine())!=null)text.append(line);}c.disconnect();if(code<200||code>=300)throw new Exception(text.toString());return new JSONArray(text.toString());
    }
    private JSONObject libraryPayload() throws Exception {JSONObject root=new JSONObject();JSONArray a=new JSONArray(),ts=new JSONArray();for(Entry e:entries)if(!e.catalogId.trim().isEmpty())a.put(e.toJson());for(Tag t:tags){JSONObject o=new JSONObject();o.put("name",t.name);o.put("for_movies",t.movie);o.put("for_series",t.series);ts.put(o);}root.put("entries",a);root.put("tags",ts);return root;}
    private void syncToServer(){network.execute(()->{try{request("PUT","/api/library",libraryPayload());}catch(Exception e){String detail=e.getMessage()==null?"未知错误":e.getMessage();if(detail.length()>120)detail=detail.substring(0,120);final String message="暂未同步："+detail;runOnUiThread(()->toast(message));}});}
    private void loadFromServer(){network.execute(()->{try{JSONArray remoteEntries=requestArray("/api/entries"),remoteTags=requestArray("/api/tags");ArrayList<Entry> nextEntries=new ArrayList<>();ArrayList<Tag> nextTags=new ArrayList<>();for(int i=0;i<remoteEntries.length();i++)nextEntries.add(Entry.from(remoteEntries.getJSONObject(i)));for(int i=0;i<remoteTags.length();i++){JSONObject t=remoteTags.getJSONObject(i);nextTags.add(new Tag(t.optString("name"),t.optBoolean("for_movies",true),t.optBoolean("for_series",true)));}entries.clear();entries.addAll(nextEntries);tags.clear();tags.addAll(nextTags);storeLocal();runOnUiThread(()->{render();toast("已从本地数据库载入片单");});}catch(Exception e){runOnUiThread(()->toast("载入失败：请检查账号和本机后端"));}});}
    private void storeLocal(){localDb.saveLibrary(entries,tags);}
    private void showAuthScreen(){root=column();root.setBackgroundColor(BG);root.setPadding(dp(26),dp(38),dp(26),dp(24));setContentView(root);TextView logo=label("CineLog",30,TEXT,true);root.addView(logo);TextView title=label("把喜欢的故事，\n收进自己的片单。",25,TEXT,true);title.setPadding(0,dp(30),0,dp(8));root.addView(title);TextView subtitle=label("登录或创建账号，记录电影、剧集与观影心情。",13,MUTED,false);root.addView(subtitle);EditText userField=input("用户名（至少 3 位）"),passwordField=input("密码（至少 6 位）");passwordField.setInputType(129);userField.setSingleLine(true);passwordField.setSingleLine(true);userField.setText(username);field(root,"用户名",userField);field(root,"密码",passwordField);TextView help=label("你的片单保存在这台电脑中，换手机或重装后登录即可恢复。",10,MUTED,false);help.setPadding(0,dp(6),0,dp(14));root.addView(help);TextView login=label("登录",13,BG,true);login.setGravity(Gravity.CENTER);login.setBackground(shape(LIME,11,0));root.addView(login,lp(-1,48));LinearLayout.LayoutParams rp=lp(-1,46);rp.topMargin=dp(9);TextView register=label("创建新账号",12,LIME,true);register.setGravity(Gravity.CENTER);register.setBackground(shape(SURFACE2,11,LINE));root.addView(register,rp);login.setOnClickListener(v->authenticate(userField.getText().toString().trim(),passwordField.getText().toString(),false));register.setOnClickListener(v->authenticate(userField.getText().toString().trim(),passwordField.getText().toString(),true));}
    private void authenticate(String account,String password,boolean register){if(account.length()<3||password.length()<6){toast("用户名至少 3 位，密码至少 6 位");return;}network.execute(()->{try{JSONObject credentials=new JSONObject();credentials.put("username",account);credentials.put("password",password);JSONObject result=request("POST",register?"/api/auth/register":"/api/auth/login",credentials);apiToken=result.getString("access_token");username=account;prefs.edit().putString("api_token",apiToken).putString("username",username).apply();if(register){request("PUT","/api/library",libraryPayload());runOnUiThread(()->{buildShell();render();toast("欢迎加入 CineLog");});}else{loadFromServerNow();runOnUiThread(()->{buildShell();render();toast("欢迎回来，"+username);});}}catch(Exception e){runOnUiThread(()->toast(register?"注册失败，请确认 CineLog 服务已运行":"登录失败，请检查账号密码"));}});}
    private void loadFromServerNow() throws Exception {JSONArray remoteEntries=requestArray("/api/entries"),remoteTags=requestArray("/api/tags");ArrayList<Entry> nextEntries=new ArrayList<>();ArrayList<Tag> nextTags=new ArrayList<>();for(int i=0;i<remoteEntries.length();i++)nextEntries.add(Entry.from(remoteEntries.getJSONObject(i)));for(int i=0;i<remoteTags.length();i++){JSONObject t=remoteTags.getJSONObject(i);nextTags.add(new Tag(t.optString("name"),t.optBoolean("for_movies",true),t.optBoolean("for_series",true)));}entries.clear();entries.addAll(nextEntries);tags.clear();tags.addAll(nextTags);storeLocal();}
    private void buildShell() {
        root=column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top,bottom;
            if(Build.VERSION.SDK_INT>=30){
                Insets safeTop=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout());
                Insets safeBottom=insets.getInsets(WindowInsets.Type.navigationBars());
                top=safeTop.top; bottom=safeBottom.bottom;
            } else { top=0; bottom=0; }
            v.setPadding(v.getPaddingLeft(),top,v.getPaddingRight(),bottom);
            return insets;
        });
        setContentView(root);
        LinearLayout top=row(); top.setPadding(dp(20),dp(10),dp(18),dp(8)); top.setBackgroundColor(BG);
        LinearLayout brand=column(); TextView logo=label("CineLog",21,TEXT,true); TextView subtitle=label("YOUR SCREEN JOURNAL",9,MUTED,true); brand.addView(logo); brand.addView(subtitle);
        top.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView account=label(username.isEmpty()?"我":username.substring(0,Math.min(2,username.length())),11,LIME,true);account.setGravity(Gravity.CENTER);account.setBackground(shape(SURFACE2,22,LINE));LinearLayout.LayoutParams accountLp=lp(42,42);accountLp.leftMargin=dp(7);top.addView(account,accountLp);account.setContentDescription("个人账号");account.setOnClickListener(v->showAccountMenu());
        TextView add=label("＋ 添加",13,BG,true); add.setGravity(Gravity.CENTER); add.setBackground(shape(LIME,15,0));
        LinearLayout.LayoutParams addLp=lp(78,46); addLp.leftMargin=dp(7); top.addView(add,addLp); add.setContentDescription("添加作品"); add.setOnClickListener(v->showEntryDialog(null));
        root.addView(top,lp(-1,68));
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=column(); content.setPadding(dp(20),dp(10),dp(20),dp(24)); scroll.addView(content);
        nav=row(); nav.setPadding(dp(5),dp(7),dp(5),dp(8)); nav.setBackgroundColor(Color.rgb(20,26,22)); root.addView(nav,lp(-1,66));
        buildNav();
    }
    private void buildNav() {
        nav.removeAllViews(); String[] icons={"⌂","✦","▣","▤","♡","☷"}; String[] names={"首页","发现","电影","剧集","想看","片单"};
        for(int i=0;i<names.length;i++) { final int idx=i; LinearLayout item=column(); item.setGravity(Gravity.CENTER); TextView icon=label(icons[i],20,page==i?LIME:MUTED,true); icon.setGravity(Gravity.CENTER); TextView name=label(names[i],10,page==i?LIME:MUTED,page==i); name.setGravity(Gravity.CENTER); item.addView(icon); item.addView(name); nav.addView(item,new LinearLayout.LayoutParams(0,-1,1)); item.setContentDescription(names[i]); item.setOnClickListener(v->{page=idx;query="";render();}); }
    }
    private void render() {
        if(content==null)return; content.removeAllViews(); buildNav();
        if(page==0) renderHome(); else if(page==1) renderDiscover(); else if(page==5) renderTags(); else renderLibrary();
    }
    private void renderHome() {
        TextView kicker=label("YOUR PERSONAL SCREEN JOURNAL",9,LIME,true); kicker.setLetterSpacing(.12f); content.addView(kicker,lp(-1,18));
        TextView title=label("把看过的故事，\n好好收藏。",28,TEXT,true); title.setLineSpacing(dp(1),1); content.addView(title,lp(-1,-2));
        TextView intro=label("记录每一次观看，也记下下一部想看的作品。",13,MUTED,false); intro.setPadding(0,dp(7),0,dp(18)); content.addView(intro);
        LinearLayout hero=column(); hero.setPadding(dp(18),dp(18),dp(18),dp(18)); hero.setBackground(shape(SURFACE2,18,LINE));
        hero.addView(label("A LITTLE MORE CINEMA",9,LIME,true)); TextView heroTitle=label("好故事，值得留下痕迹。",19,TEXT,true); heroTitle.setPadding(0,dp(11),0,dp(6)); hero.addView(heroTitle);
        hero.addView(label("记录电影与剧集，写下短评，整理自己的观影片单。登录后会自动同步到你的 CineLog 账号。",12,MUTED,false));
        TextView cta=label("＋ 记录一部作品",12,BG,true); cta.setGravity(Gravity.CENTER); cta.setBackground(shape(LIME,10,0)); LinearLayout.LayoutParams cp=lp(152,42);cp.topMargin=dp(16);hero.addView(cta,cp);cta.setOnClickListener(v->showEntryDialog(null));
        LinearLayout.LayoutParams hp=lp(-1,-2);hp.topMargin=dp(4);hp.bottomMargin=dp(16);content.addView(hero,hp);
        int watched=0,wishlist=0;double score=0;int rated=0;for(Entry e:entries){if(e.wishlist)wishlist++;else{watched++;if(e.rating>0){score+=e.rating;rated++;}}}
        LinearLayout stats=row();stats.addView(stat("已看作品",String.valueOf(watched)),new LinearLayout.LayoutParams(0,dp(82),1));
        View gap=new View(this);stats.addView(gap,lp(9,1));stats.addView(stat("想看清单",String.valueOf(wishlist)),new LinearLayout.LayoutParams(0,dp(82),1));
        View gap2=new View(this);stats.addView(gap2,lp(9,1));stats.addView(stat("平均评分",rated==0?"—":String.format(Locale.CHINA,"%.1f",score/rated)),new LinearLayout.LayoutParams(0,dp(82),1));content.addView(stats);
        LinearLayout section=row();section.setPadding(0,dp(22),0,dp(10));section.addView(label("最近记录",17,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));TextView all=label("查看全部  →",11,LIME,true);section.addView(all);all.setOnClickListener(v->{page=2;render();});content.addView(section);
        ArrayList<Entry> recents=new ArrayList<>();for(Entry e:entries)if(!e.wishlist)recents.add(e);
        if(recents.isEmpty())content.addView(empty("还没有观影记录","添加第一部作品，开始建立你的片单。"));else for(int i=0;i<Math.min(3,recents.size());i++)content.addView(entryCard(recents.get(i)));
    }
    private View stat(String title,String value) {LinearLayout box=column();box.setPadding(dp(11),dp(12),dp(8),dp(8));box.setBackground(shape(SURFACE,13,LINE));box.addView(label(title,10,MUTED,false));TextView n=label(value,22,LIME,true);n.setPadding(0,dp(7),0,0);box.addView(n);return box;}
    private void renderLibrary() {
        String title=page==2?"电影日志":page==3?"剧集日志":"想看清单";
        TextView kicker=label("CINELOG LIBRARY",9,LIME,true);kicker.setLetterSpacing(.13f);content.addView(kicker);
        TextView h=label(title,27,TEXT,true);h.setPadding(0,dp(6),0,dp(4));content.addView(h);
        content.addView(label(page==4?"把感兴趣的电影和剧集先收进清单。":"整理作品记录，留下每一次观影感受。",12,MUTED,false));
        searchField=new EditText(this);searchField.setSingleLine(true);searchField.setTextSize(13);searchField.setTextColor(TEXT);searchField.setHintTextColor(MUTED);searchField.setHint("⌕  搜索片名、短评或标签");searchField.setPadding(dp(14),0,dp(14),0);searchField.setBackground(shape(SURFACE,11,LINE));LinearLayout.LayoutParams sp=lp(-1,46);sp.topMargin=dp(17);sp.bottomMargin=dp(12);content.addView(searchField,sp);
        LinearLayout filters=row();filters.setGravity(Gravity.CENTER_VERTICAL);String[] fs={"全部","已看","想看"};for(int i=0;i<fs.length;i++){final String f=fs[i];TextView chip=label(f,10,TEXT,true);chip.setGravity(Gravity.CENTER);chip.setPadding(dp(14),0,dp(14),0);chip.setBackground(shape(SURFACE2,30,LINE));LinearLayout.LayoutParams fl=lp(-2,34);fl.rightMargin=dp(7);filters.addView(chip,fl);chip.setOnClickListener(v->{renderFiltered(f);});}content.addView(filters);
        LinearLayout list=column();list.setTag("entry-list");LinearLayout.LayoutParams listp=lp(-1,-2);listp.topMargin=dp(13);content.addView(list,listp);fillEntries(list,"全部");
        searchField.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();fillEntries(list,"全部");}public void afterTextChanged(android.text.Editable e){}});
        TextView add=label("＋  添加条目",13,BG,true);add.setGravity(Gravity.CENTER);add.setBackground(shape(LIME,12,0));LinearLayout.LayoutParams ap=lp(-1,48);ap.topMargin=dp(17);content.addView(add,ap);add.setOnClickListener(v->showEntryDialog(null));
    }
    private void renderFiltered(String filter){View v=content.findViewWithTag("entry-list");if(v instanceof LinearLayout)fillEntries((LinearLayout)v,filter);}
    private void fillEntries(LinearLayout list,String filter){list.removeAllViews();int count=0;for(Entry e:entries){if(page==2&&(e.series||e.wishlist))continue;if(page==3&&(!e.series||e.wishlist))continue;if(page==4&&!e.wishlist)continue;if(filter.equals("已看")&&e.wishlist)continue;if(filter.equals("想看")&&!e.wishlist)continue;String hay=(e.title+" "+e.review+" "+e.tags).toLowerCase(Locale.ROOT);if(!query.isEmpty()&&!hay.contains(query.toLowerCase(Locale.ROOT)))continue;list.addView(entryCard(e));count++;}if(count==0)list.addView(empty("没有找到作品","调整搜索条件，或添加一条新记录。"));}
    private void renderDiscover(){TextView k=label("DISCOVER",9,LIME,true);content.addView(k);TextView h=label("为你发现",27,TEXT,true);h.setPadding(0,dp(6),0,dp(4));content.addView(h);content.addView(label("根据已看高分标签排序，并结合个人片单做随机探索。",12,MUTED,false));LinearLayout list=column();content.addView(list,lp(-1,-2));TextView loading=label("正在为你挑选作品…",12,MUTED,false);list.addView(loading);network.execute(()->{try{JSONArray picks=requestArray("/api/recommendations");runOnUiThread(()->{list.removeAllViews();if(picks.length()==0){list.addView(empty("片单已经探索完了","先记录或加入更多作品，我们再为你推荐。"));return;}for(int i=0;i<picks.length();i++){JSONObject p=picks.optJSONObject(i);if(p==null)continue;Entry candidate=Entry.from(p);LinearLayout card=row();card.setPadding(dp(13),dp(13),dp(13),dp(13));card.setBackground(shape(SURFACE,14,LINE));LinearLayout info=column();info.addView(label(candidate.title,15,TEXT,true));info.addView(label((candidate.series?"剧集":"电影")+(candidate.year.isEmpty()?"":" · "+candidate.year),10,MUTED,false));info.addView(label(p.optString("reason","根据你的片单推荐"),10,LIME,false));card.addView(info,new LinearLayout.LayoutParams(0,-2,1));TextView add=label("＋ 想看",11,BG,true);add.setGravity(Gravity.CENTER);add.setBackground(shape(LIME,10,0));card.addView(add,lp(70,38));add.setOnClickListener(v->{candidate.id=java.util.UUID.randomUUID().toString();candidate.wishlist=true;candidate.rating=0;candidate.review="";candidate.date="";entries.add(0,candidate);registerInlineTags(candidate.tags);save();render();toast("已加入想看清单");});LinearLayout.LayoutParams cp=lp(-1,-2);cp.bottomMargin=dp(10);list.addView(card,cp);}});}catch(Exception e){runOnUiThread(()->{list.removeAllViews();list.addView(empty("暂时无法获取推荐","请确认电脑上的 CineLog 服务正在运行。"));});}});}
    private void showAccountMenu(){new AlertDialog.Builder(this).setTitle(username).setItems(new String[]{"刷新片单","管理员监测","退出登录"},(d,which)->{if(which==0)loadFromServer();else if(which==1){try{startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apiBaseUrl+"/admin")));}catch(Exception ignored){toast("无法打开管理员后台");}}else{apiToken="";prefs.edit().remove("api_token").apply();showAuthScreen();}}).setNegativeButton("关闭",null).show();}
    private View entryCard(Entry e){LinearLayout card=row();card.setGravity(Gravity.TOP);card.setPadding(dp(11),dp(11),dp(11),dp(11));card.setBackground(shape(SURFACE,14,LINE));LinearLayout.LayoutParams outer=lp(-1,-2);outer.bottomMargin=dp(10);card.setLayoutParams(outer);
        LinearLayout poster=column();poster.setGravity(Gravity.CENTER);poster.setBackground(shape(e.series?Color.rgb(58,79,82):Color.rgb(94,67,54),10,0));TextView initials=label(shortTitle(e.title),16,TEXT,true);initials.setGravity(Gravity.CENTER);poster.addView(initials);card.addView(poster,lp(68,96));
        LinearLayout info=column();info.setPadding(dp(12),dp(1),0,0);card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout titleRow=row();titleRow.addView(label(e.title,15,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));TextView dots=label("⋮",22,MUTED,true);dots.setGravity(Gravity.CENTER);titleRow.addView(dots,lp(28,28));dots.setOnClickListener(v->showEntryActions(e));info.addView(titleRow);
        TextView meta=label((e.series?"剧集":"电影")+"  ·  "+(e.wishlist?"想看":"已看"),10,MUTED,false);meta.setPadding(0,dp(4),0,dp(2));info.addView(meta);
        if(!e.wishlist){TextView stars=label(stars(e.rating),13,LIME,true);stars.setPadding(0,dp(2),0,dp(3));info.addView(stars);}
        if(!e.review.trim().isEmpty()){TextView review=label(e.review,11,MUTED,false);review.setMaxLines(2);info.addView(review);}
        LinearLayout chips=row();if(!e.tags.trim().isEmpty()){for(String tag:e.tags.split("[,，]")){TextView tv=label(tag.trim(),9,LIME,true);tv.setPadding(dp(8),dp(4),dp(8),dp(4));tv.setBackground(shape(Color.rgb(39,52,37),20,0));LinearLayout.LayoutParams tp=lp(-2,-2);tp.topMargin=dp(7);tp.rightMargin=dp(5);chips.addView(tv,tp);}}info.addView(chips);
        card.setOnClickListener(v->showEntryActions(e));return card;
    }
    private String shortTitle(String s){return s.length()>6?s.substring(0,6):s;}
    private String stars(int n){StringBuilder b=new StringBuilder();for(int i=0;i<5;i++)b.append(i<n?"★":"☆");return b.toString();}
    private View empty(String title,String body){LinearLayout box=column();box.setGravity(Gravity.CENTER);box.setPadding(dp(18),dp(26),dp(18),dp(26));box.setBackground(shape(SURFACE,14,LINE));TextView mark=label("✦",24,LIME,true);mark.setGravity(Gravity.CENTER);box.addView(mark);TextView h=label(title,14,TEXT,true);h.setGravity(Gravity.CENTER);h.setPadding(0,dp(9),0,dp(5));box.addView(h);TextView b=label(body,11,MUTED,false);b.setGravity(Gravity.CENTER);box.addView(b);return box;}
    private void renderTags(){TextView k=label("ORGANIZE",9,LIME,true);content.addView(k);TextView h=label("标签管理",27,TEXT,true);h.setPadding(0,dp(6),0,dp(4));content.addView(h);content.addView(label("创建自己的分类，并选择适用的作品类型。",12,MUTED,false));TextView add=label("＋  创建标签",12,BG,true);add.setGravity(Gravity.CENTER);add.setBackground(shape(LIME,11,0));LinearLayout.LayoutParams ap=lp(-1,46);ap.topMargin=dp(18);ap.bottomMargin=dp(13);content.addView(add,ap);add.setOnClickListener(v->showTagDialog());
        for(Tag tag:tags){LinearLayout r=row();r.setPadding(dp(13),dp(12),dp(13),dp(12));r.setBackground(shape(SURFACE,12,LINE));LinearLayout.LayoutParams rp=lp(-1,-2);rp.bottomMargin=dp(9);content.addView(r,rp);TextView icon=label("⌗",20,LIME,true);r.addView(icon,lp(35,35));LinearLayout info=column();info.addView(label(tag.name,14,TEXT,true));info.addView(label((tag.movie?"电影":"")+(tag.movie&&tag.series?"  ·  ":"")+(tag.series?"剧集":""),10,MUTED,false));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.leftMargin=dp(8);r.addView(info,ip);TextView del=label("删除",10,Color.rgb(214,133,112),true);r.addView(del);del.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("删除标签“"+tag.name+"”？").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{tags.remove(tag);for(Entry e:entries)e.tags=removeTag(e.tags,tag.name);save();render();}).show());}
    }
    private String removeTag(String list,String target){ArrayList<String> out=new ArrayList<>();for(String s:list.split("[,，]"))if(!s.trim().equals(target)&&!s.trim().isEmpty())out.add(s.trim());return String.join(", ",out);}
    private void showEntryActions(Entry e){String[] actions={"查看详情 / 编辑","标记为"+(e.wishlist?"已看":"想看"),"删除作品"};new AlertDialog.Builder(this).setTitle(e.title).setItems(actions,(d,n)->{if(n==0)showEntryDialog(e);else if(n==1){e.wishlist=!e.wishlist;if(!e.wishlist&&e.date.isEmpty())e.date=today();save();render();}else new AlertDialog.Builder(this).setMessage("删除这条作品记录？").setNegativeButton("取消",null).setPositiveButton("删除",(x,y)->{entries.remove(e);save();render();}).show();}).setNegativeButton("关闭",null).show();}
    private void showEntryDialog(Entry existing){
        LinearLayout form=column();form.setPadding(dp(20),0,dp(20),0);EditText title=input("输入片名后点击搜索");title.setSingleLine(true);Spinner type=spinner(new String[]{"电影","剧集"});Spinner status=spinner(new String[]{"已看","想看"});Spinner rating=spinner(new String[]{"未评分","1 星","2 星","3 星","4 星","5 星"});EditText review=input("短评");EditText tagText=input("标签，可用逗号分隔");String[] selectedCatalog={existing==null?"":existing.catalogId};
        LinearLayout titleRow=row();titleRow.addView(title,new LinearLayout.LayoutParams(0,dp(46),1));TextView search=label("搜索",11,LIME,true);search.setGravity(Gravity.CENTER);LinearLayout.LayoutParams searchLp=lp(68,42);searchLp.leftMargin=dp(7);titleRow.addView(search,searchLp);search.setOnClickListener(v->searchCatalog(title,type,tagText,selectedCatalog));TextView titleLabel=label("作品片名（必须从目录中选择）",11,MUTED,true);form.addView(titleLabel);form.addView(titleRow);
        field(form,"状态",status);field(form,"评分",rating);field(form,"短评",review);field(form,"标签",tagText);
        if(existing!=null){title.setText(existing.title);type.setSelection(existing.series?1:0);status.setSelection(existing.wishlist?1:0);rating.setSelection(Math.max(0,Math.min(5,existing.rating)));review.setText(existing.review);tagText.setText(existing.tags);}
        ScrollView sc=new ScrollView(this);sc.addView(form);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"添加作品":"编辑作品").setView(sc).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(btn->{String name=title.getText().toString().trim();if(name.isEmpty()||selectedCatalog[0].isEmpty()){toast("请先搜索并选择有效作品");return;}Entry e=existing==null?new Entry():existing;e.catalogId=selectedCatalog[0];e.title=name;e.series=type.getSelectedItemPosition()==1;e.wishlist=status.getSelectedItemPosition()==1;e.rating=e.wishlist?0:rating.getSelectedItemPosition();e.review=e.wishlist?"":review.getText().toString().trim();e.tags=tagText.getText().toString().trim();if(e.date.isEmpty())e.date=today();if(existing==null){e.id=java.util.UUID.randomUUID().toString();entries.add(0,e);}registerInlineTags(e.tags);save();dialog.dismiss();render();}));dialog.show();
    }
    private void searchCatalog(EditText title,Spinner type,EditText tagText,String[] selected){String q=title.getText().toString().trim();if(q.length()<2){toast("请输入至少两个字再搜索");return;}network.execute(()->{try{String kind=type.getSelectedItemPosition()==1?"SERIES":"MOVIE";JSONArray results=requestArray("/api/catalog/search?q="+java.net.URLEncoder.encode(q,"UTF-8")+"&kind="+kind);runOnUiThread(()->{if(results.length()==0){toast("目录中没有匹配作品，请检查片名");return;}String[] labels=new String[results.length()];for(int i=0;i<results.length();i++){JSONObject x=results.optJSONObject(i);labels[i]=x.optString("title")+(x.optString("year").isEmpty()?"":" · "+x.optString("year"));}new AlertDialog.Builder(this).setTitle("选择正确的作品").setItems(labels,(d,which)->{JSONObject x=results.optJSONObject(which);title.setText(x.optString("title"));selected[0]=x.optString("catalog_id");JSONArray tagsArray=x.optJSONArray("tags");ArrayList<String> merged=new ArrayList<>();if(tagsArray!=null)for(int i=0;i<tagsArray.length();i++)merged.add(tagsArray.optString(i));String userTags=tagText.getText().toString().trim();if(!userTags.isEmpty())merged.add(userTags);tagText.setText(String.join(", ",merged));}).setNegativeButton("取消",null).show();});}catch(Exception e){String detail=e.getMessage()==null?"未知错误":e.getMessage();if(detail.length()>100)detail=detail.substring(0,100);final String message="搜索失败："+detail;runOnUiThread(()->toast(message));}});}
    private EditText input(String hint){EditText e=new EditText(this);e.setSingleLine(false);e.setTextSize(14);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setHint(hint);e.setPadding(dp(13),dp(10),dp(13),dp(10));e.setBackground(shape(SURFACE2,10,LINE));return e;}
    private Spinner spinner(String[] items){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items));s.setBackground(shape(SURFACE2,10,LINE));s.setPadding(dp(10),0,dp(10),0);return s;}
    private void field(LinearLayout parent,String name,View control){TextView l=label(name,11,MUTED,true);LinearLayout.LayoutParams tl=lp(-1,-2);tl.topMargin=dp(9);tl.bottomMargin=dp(4);parent.addView(l,tl);parent.addView(control,lp(-1,46));}
    private void registerInlineTags(String csv){for(String name:csv.split("[,，]")){String n=name.trim();if(!n.isEmpty()&&findTag(n)==null)tags.add(new Tag(n,true,true));}}
    private Tag findTag(String name){for(Tag t:tags)if(t.name.equalsIgnoreCase(name))return t;return null;}
    private void showTagDialog(){LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);EditText name=input("标签名称");field(box,"标签名称",name);CheckBox movie=new CheckBox(this);movie.setText("适用于电影");movie.setTextColor(TEXT);movie.setButtonTintList(ColorStateList.valueOf(LIME));CheckBox series=new CheckBox(this);series.setText("适用于剧集");series.setTextColor(TEXT);series.setChecked(true);series.setButtonTintList(ColorStateList.valueOf(LIME));movie.setChecked(true);box.addView(movie);box.addView(series);new AlertDialog.Builder(this).setTitle("创建标签").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String n=name.getText().toString().trim();if(n.isEmpty()){toast("请输入标签名称");return;}if(!movie.isChecked()&&!series.isChecked()){toast("至少选择一种作品类型");return;}if(findTag(n)!=null){toast("标签已存在");return;}tags.add(new Tag(n,movie.isChecked(),series.isChecked()));save();render();}).show();}
    private String today(){return new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(new Date());}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    static class Tag {String name;boolean movie,series;Tag(String n,boolean m,boolean s){name=n;movie=m;series=s;}JSONObject toJson()throws Exception{JSONObject o=new JSONObject();o.put("name",name);o.put("movie",movie);o.put("series",series);return o;}}
    static class Entry {String id="",catalogId="",title="",review="",tags="",date="",year="";boolean series,wishlist;int rating;String[] tagNames(){return tags.split("[,，]");}JSONObject toJson()throws Exception{JSONObject o=new JSONObject();if(!catalogId.isEmpty())o.put("catalog_id",catalogId);o.put("title",title);o.put("review",review);o.put("tags",new JSONArray());JSONArray tagArray=o.getJSONArray("tags");for(String t:tags.split("[,，]"))if(!t.trim().isEmpty())tagArray.put(t.trim());o.put("watched_date",date.isEmpty()?JSONObject.NULL:date);o.put("kind",series?"SERIES":"MOVIE");o.put("state",wishlist?"WISHLIST":"WATCHED");o.put("rating",rating);return o;}static Entry from(JSONObject o){Entry e=new Entry();e.id=o.optString("id",java.util.UUID.randomUUID().toString());e.catalogId=o.optString("catalog_id");e.title=o.optString("title");e.review=o.optString("review");e.date=o.has("watched_date")?o.optString("watched_date"):o.optString("date");e.year=o.optString("year");e.series="SERIES".equals(o.optString("kind"))||o.optBoolean("series");e.wishlist="WISHLIST".equals(o.optString("state"))||o.optBoolean("wishlist");e.rating=o.optInt("rating");if(o.has("tags")&&o.opt("tags") instanceof JSONArray){JSONArray a=o.optJSONArray("tags");StringBuilder b=new StringBuilder();for(int i=0;i<a.length();i++){if(i>0)b.append(", ");b.append(a.optString(i));}e.tags=b.toString();}else e.tags=o.optString("tags");return e;}}
}
