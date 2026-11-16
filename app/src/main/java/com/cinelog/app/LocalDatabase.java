package com.cinelog.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Private on-device cache. Tags and entries are normalized and related by entry_tags. */
final class LocalDatabase extends SQLiteOpenHelper {
    private static final String NAME = "cinelog.db";
    private static final int VERSION = 2;

    LocalDatabase(Context context) { super(context, NAME, null, VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY, catalog_id TEXT, title TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, rating INTEGER NOT NULL DEFAULT 0, review TEXT NOT NULL DEFAULT '', watched_date TEXT, year TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, for_movies INTEGER NOT NULL DEFAULT 1, for_series INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE entry_tags (entry_id TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE, tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE, PRIMARY KEY(entry_id, tag_id))");
        db.execSQL("CREATE TABLE app_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { if(oldVersion<2) db.execSQL("ALTER TABLE entries ADD COLUMN catalog_id TEXT"); }

    void saveLibrary(ArrayList<MainActivity.Entry> entries, ArrayList<MainActivity.Tag> tags) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("entry_tags", null, null); db.delete("entries", null, null); db.delete("tags", null, null);
            for (MainActivity.Tag tag : tags) { ContentValues v = new ContentValues(); v.put("name", tag.name); v.put("for_movies", tag.movie?1:0); v.put("for_series", tag.series?1:0); db.insertOrThrow("tags", null, v); }
            for (MainActivity.Entry e : entries) {
                ContentValues v = new ContentValues(); v.put("id", e.id); v.put("catalog_id", e.catalogId); v.put("title", e.title); v.put("kind", e.series?"SERIES":"MOVIE"); v.put("state", e.wishlist?"WISHLIST":"WATCHED"); v.put("rating", e.rating); v.put("review", e.review); v.put("watched_date", e.date.isEmpty()?null:e.date); v.put("year", e.year); db.insertOrThrow("entries", null, v);
                for (String name : e.tagNames()) { long tagId = findTag(db, name); if (tagId < 0) { ContentValues tv = new ContentValues(); tv.put("name", name); tv.put("for_movies",1); tv.put("for_series",1); tagId=db.insertOrThrow("tags",null,tv); } ContentValues link=new ContentValues();link.put("entry_id",e.id);link.put("tag_id",tagId);db.insertWithOnConflict("entry_tags",null,link,SQLiteDatabase.CONFLICT_IGNORE); }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private long findTag(SQLiteDatabase db,String name) { try(Cursor c=db.rawQuery("SELECT id FROM tags WHERE name = ? COLLATE NOCASE",new String[]{name})){return c.moveToFirst()?c.getLong(0):-1;} }

    void loadLibrary(ArrayList<MainActivity.Entry> entries, ArrayList<MainActivity.Tag> tags) {
        SQLiteDatabase db=getReadableDatabase();entries.clear();tags.clear();
        try(Cursor c=db.rawQuery("SELECT name,for_movies,for_series FROM tags ORDER BY name",null)){while(c.moveToNext())tags.add(new MainActivity.Tag(c.getString(0),c.getInt(1)!=0,c.getInt(2)!=0));}
        try(Cursor c=db.rawQuery("SELECT id,catalog_id,title,kind,state,rating,review,watched_date,year FROM entries ORDER BY rowid DESC",null)){
            while(c.moveToNext()) {MainActivity.Entry e=new MainActivity.Entry();e.id=c.getString(0);e.catalogId=c.isNull(1)?"":c.getString(1);e.title=c.getString(2);e.series="SERIES".equals(c.getString(3));e.wishlist="WISHLIST".equals(c.getString(4));e.rating=c.getInt(5);e.review=c.getString(6);e.date=c.isNull(7)?"":c.getString(7);e.year=c.getString(8);
                try(Cursor t=db.rawQuery("SELECT tags.name FROM tags INNER JOIN entry_tags ON tags.id=entry_tags.tag_id WHERE entry_tags.entry_id=? ORDER BY tags.name",new String[]{e.id})){StringBuilder b=new StringBuilder();while(t.moveToNext()){if(b.length()>0)b.append(", ");b.append(t.getString(0));}e.tags=b.toString();}
                entries.add(e);
            }
        }
    }

    void putState(String key,String value){ContentValues v=new ContentValues();v.put("key",key);v.put("value",value);getWritableDatabase().insertWithOnConflict("app_state",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    String getState(String key){try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM app_state WHERE key=?",new String[]{key})){return c.moveToFirst()?c.getString(0):"";}}
}
