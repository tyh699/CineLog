import base64
import hashlib
import hmac
import json
import os
import secrets
import random
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Generator

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine, select, func, inspect, text
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker


DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./cinelog.db")
TMDB_API_READ_TOKEN = os.getenv("TMDB_API_READ_TOKEN", "").strip()
JWT_SECRET = os.getenv("JWT_SECRET", "")
TOKEN_HOURS = int(os.getenv("TOKEN_HOURS", "168"))
if len(JWT_SECRET) < 32:
    if os.getenv("APP_ENV", "development") == "production":
        raise RuntimeError("JWT_SECRET must be at least 32 characters in production")
    JWT_SECRET = "local-dev-only-" + "x" * 48

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {})
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(256))
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class Entry(Base):
    __tablename__ = "entries"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    catalog_id: Mapped[str | None] = mapped_column(String(80), nullable=True)
    title: Mapped[str] = mapped_column(String(160))
    kind: Mapped[str] = mapped_column(String(10))
    state: Mapped[str] = mapped_column(String(12))
    rating: Mapped[int | None] = mapped_column(Integer, nullable=True)
    review: Mapped[str] = mapped_column(Text, default="")
    watched_date: Mapped[str | None] = mapped_column(String(10), nullable=True)
    tags: Mapped[str] = mapped_column(Text, default="[]")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))


class Tag(Base):
    __tablename__ = "tags"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(48))
    for_movies: Mapped[bool] = mapped_column(Boolean, default=True)
    for_series: Mapped[bool] = mapped_column(Boolean, default=True)


Base.metadata.create_all(engine)
# Small forward migration for existing local SQLite files.
if "is_admin" not in {column["name"] for column in inspect(engine).get_columns("users")}:
    with engine.begin() as connection:
        connection.execute(text("ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT 0"))
if "catalog_id" not in {column["name"] for column in inspect(engine).get_columns("entries")}:
    with engine.begin() as connection:
        connection.execute(text("ALTER TABLE entries ADD COLUMN catalog_id VARCHAR(80)"))
# In this single-computer homework setup, promote the oldest existing account
# once, so upgrading a database created before admin monitoring stays usable.
with SessionLocal() as migration_db:
    if migration_db.scalar(select(User.id).where(User.is_admin.is_(True)).limit(1)) is None:
        first_id = migration_db.scalar(select(User.id).order_by(User.id).limit(1))
        if first_id is not None:
            migration_db.get(User, first_id).is_admin = True
            migration_db.commit()
app = FastAPI(title="CineLog API", version="1.0.0", description="Private, user-scoped CineLog API")
app.add_middleware(CORSMiddleware, allow_origins=os.getenv("CORS_ORIGINS", "*").split(","), allow_credentials=False, allow_methods=["GET", "POST", "PATCH", "DELETE"], allow_headers=["Authorization", "Content-Type"])
bearer = HTTPBearer(auto_error=False)

# The browser client is part of the same local app, so a single uvicorn
# process provides both the API and the UI.  This keeps the default setup
# entirely local and avoids a separate frontend server.
STATIC_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "static"))


def db_session() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def password_digest(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 310_000)
    return f"pbkdf2_sha256$310000${b64(salt)}${b64(digest)}"


def verify_password(password: str, encoded: str) -> bool:
    try:
        algorithm, rounds, salt, expected = encoded.split("$")
        if algorithm != "pbkdf2_sha256":
            return False
        candidate = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.urlsafe_b64decode(salt + "=="), int(rounds))
        return hmac.compare_digest(b64(candidate), expected)
    except (ValueError, TypeError):
        return False


def create_token(user_id: int) -> str:
    now = datetime.now(timezone.utc)
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64(json.dumps({"sub": str(user_id), "iat": int(now.timestamp()), "exp": int((now + timedelta(hours=TOKEN_HOURS)).timestamp())}, separators=(",", ":")).encode())
    content = f"{header}.{payload}"
    signature = b64(hmac.new(JWT_SECRET.encode(), content.encode(), hashlib.sha256).digest())
    return f"{content}.{signature}"


def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db: Session = Depends(db_session)) -> User:
    if not credentials or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=401, detail="Authentication required")
    try:
        head, body, signature = credentials.credentials.split(".")
        message = f"{head}.{body}"
        expected = b64(hmac.new(JWT_SECRET.encode(), message.encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(signature, expected):
            raise ValueError("signature")
        payload = json.loads(base64.urlsafe_b64decode(body + "=="))
        if int(payload["exp"]) < int(datetime.now(timezone.utc).timestamp()):
            raise ValueError("expired")
        user = db.get(User, int(payload["sub"]))
        if user is None:
            raise ValueError("missing user")
        return user
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        raise HTTPException(status_code=401, detail="Invalid or expired token")


class CredentialsIn(BaseModel):
    username: str = Field(min_length=3, max_length=64, pattern=r"^[a-zA-Z0-9_.-]+$")
    password: str = Field(min_length=6, max_length=128)


class EntryIn(BaseModel):
    catalog_id: str | None = Field(default=None, max_length=80)
    title: str = Field(min_length=1, max_length=160)
    kind: str = Field(pattern=r"^(MOVIE|SERIES)$")
    state: str = Field(pattern=r"^(WATCHED|WISHLIST)$")
    rating: int | None = Field(default=None, ge=0, le=5)
    review: str = Field(default="", max_length=5000)
    watched_date: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    tags: list[str] = Field(default_factory=list, max_length=40)


class EntryOut(EntryIn):
    model_config = ConfigDict(from_attributes=True)
    id: int
    created_at: datetime
    updated_at: datetime


class TagIn(BaseModel):
    name: str = Field(min_length=1, max_length=48)
    for_movies: bool = True
    for_series: bool = True


class TagOut(TagIn):
    model_config = ConfigDict(from_attributes=True)
    id: int


class LibraryIn(BaseModel):
    entries: list[EntryIn] = Field(default_factory=list, max_length=2000)
    tags: list[TagIn] = Field(default_factory=list, max_length=300)


class CatalogPick(BaseModel):
    catalog_id: str = Field(min_length=1, max_length=80)
    title: str = Field(min_length=1, max_length=160)
    kind: str = Field(pattern=r"^(MOVIE|SERIES)$")
    year: str = Field(default="", max_length=4)
    tags: list[str] = Field(default_factory=list, max_length=20)


CATALOG = [
    ("movie-inception", "盗梦空间", "MOVIE", "2010", ["科幻", "悬疑"]), ("movie-interstellar", "星际穿越", "MOVIE", "2014", ["科幻", "经典"]),
    ("movie-mood", "花样年华", "MOVIE", "2000", ["爱情", "经典"]), ("movie-parasite", "寄生虫", "MOVIE", "2019", ["悬疑", "社会"]),
    ("movie-spirited-away", "千与千寻", "MOVIE", "2001", ["动画", "成长"]), ("movie-arrival", "降临", "MOVIE", "2016", ["科幻", "治愈"]),
    ("movie-little-forest", "小森林", "MOVIE", "2018", ["治愈", "美食"]), ("movie-past-lives", "过往人生", "MOVIE", "2023", ["爱情", "成长"]),
    ("movie-la-la-land", "爱乐之城", "MOVIE", "2016", ["爱情", "音乐"]), ("movie-shoplifters", "小偷家族", "MOVIE", "2018", ["家庭", "社会"]),
    ("series-severance", "人生切割术", "SERIES", "2022", ["科幻", "悬疑"]), ("series-severance-en", "Severance", "SERIES", "2022", ["科幻", "悬疑"]), ("series-long-season", "漫长的季节", "SERIES", "2023", ["悬疑", "国产"]),
    ("series-bear", "熊家餐馆", "SERIES", "2022", ["美食", "家庭"]), ("series-breaking-bad", "绝命毒师", "SERIES", "2008", ["犯罪", "经典"]),
    ("series-friends", "老友记", "SERIES", "1994", ["喜剧", "友情"]), ("series-hospital-playlist", "机智医生生活", "SERIES", "2020", ["友情", "治愈"]),
    ("series-dark", "暗黑", "SERIES", "2017", ["科幻", "悬疑"]), ("series-fleabag", "伦敦生活", "SERIES", "2016", ["喜剧", "成长"]),
    ("movie-arrival-en", "Arrival", "MOVIE", "2016", ["科幻", "治愈"]), ("movie-past-lives-en", "Past Lives", "MOVIE", "2023", ["爱情", "成长"]),
]
TMDB_CACHE: dict[str, dict] = {}


def catalog_items():
    return [{"catalog_id": cid, "title": title, "kind": kind, "year": year, "tags": tags} for cid, title, kind, year, tags in CATALOG]


def tmdb_search(query: str, kind: str) -> list[dict]:
    if not TMDB_API_READ_TOKEN:
        return []
    endpoint = "movie" if kind == "MOVIE" else "tv"
    url = "https://api.themoviedb.org/3/search/" + endpoint + "?language=zh-CN&include_adult=false&query=" + urllib.parse.quote(query)
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {TMDB_API_READ_TOKEN}", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except Exception:
        return []
    results = []
    for item in payload.get("results", [])[:8]:
        title = item.get("title") if kind == "MOVIE" else item.get("name")
        date = item.get("release_date") if kind == "MOVIE" else item.get("first_air_date")
        if not title:
            continue
        canonical = {"catalog_id": f"tmdb-{kind.lower()}-{item.get('id')}", "title": title, "kind": kind, "year": (date or "")[:4], "tags": [], "source": "TMDB", "poster_path": item.get("poster_path") or ""}
        TMDB_CACHE[canonical["catalog_id"]] = canonical
        results.append(canonical)
    return results


def entry_out(entry: Entry) -> dict:
    return {"id": entry.id, "catalog_id": entry.catalog_id, "title": entry.title, "kind": entry.kind, "state": entry.state, "rating": entry.rating, "review": entry.review, "watched_date": entry.watched_date, "tags": json.loads(entry.tags), "created_at": entry.created_at, "updated_at": entry.updated_at}


def canonical_entry(data: EntryIn) -> tuple[str, str, list[str], str]:
    match = next((item for item in catalog_items() + list(TMDB_CACHE.values()) if (data.catalog_id and item["catalog_id"] == data.catalog_id and item["kind"] == data.kind) or (not data.catalog_id and item["title"].casefold() == data.title.strip().casefold() and item["kind"] == data.kind)), None)
    if match is None:
        raise HTTPException(422, "Please choose a title from catalog search")
    return match["catalog_id"], match["title"], match["tags"], match["year"]


@app.get("/health")
def health():
    return {"status": "ok", "service": "cinelog-api"}


@app.get("/api/stats")
def stats(user: User = Depends(current_user), db: Session = Depends(db_session)):
    items = db.scalars(select(Entry).where(Entry.owner_id == user.id)).all()
    watched = [e for e in items if e.state == "WATCHED"]
    rated = [e.rating for e in watched if e.rating]
    return {"watched": len(watched), "wishlist": len(items) - len(watched), "average_rating": round(sum(rated) / len(rated), 1) if rated else None, "by_kind": {"MOVIE": sum(e.kind == "MOVIE" for e in watched), "SERIES": sum(e.kind == "SERIES" for e in watched)}}


@app.get("/api/recommendations")
def recommendations(user: User = Depends(current_user), db: Session = Depends(db_session)):
    """Personalized local catalog recommendations with a user-specific tie-breaker."""
    items = db.scalars(select(Entry).where(Entry.owner_id == user.id)).all()
    watched = [e for e in items if e.state == "WATCHED"]
    wishlist = [e for e in items if e.state == "WISHLIST"]
    liked = {tag for e in watched if (e.rating or 0) >= 4 for tag in json.loads(e.tags)}
    excluded = {e.title.casefold() for e in items}
    seed = sum(ord(ch) for ch in str(user.id)) + len(items) * 31
    rng = random.Random(seed)
    candidates = [item for item in catalog_items() if item["title"].casefold() not in excluded]
    rng.shuffle(candidates)
    candidates.sort(key=lambda item: len(liked.intersection(item["tags"])), reverse=True)
    if len(candidates) > 1:
        offset = user.id % min(len(candidates), 5)
        candidates = candidates[offset:] + candidates[:offset]
    return [{**item, "reason": "与你的高分标签相似" if liked.intersection(item["tags"]) else "根据你的片单随机发现"} for item in candidates[:12]]


@app.get("/api/catalog/search")
def catalog_search(q: str = "", kind: str = "MOVIE", user: User = Depends(current_user)):
    """Return selectable canonical titles; arbitrary user-entered names are never accepted by create/update."""
    needle = q.strip().casefold()
    if len(needle) < 2 or kind not in {"MOVIE", "SERIES"}:
        return []
    local = [item for item in catalog_items() if item["kind"] == kind and needle in item["title"].casefold()]
    remote = tmdb_search(q.strip(), kind)
    seen = {item["title"].casefold() for item in local}
    return local + [item for item in remote if item["title"].casefold() not in seen]


@app.post("/api/auth/register", status_code=201)
def register(data: CredentialsIn, db: Session = Depends(db_session)):
    if db.scalar(select(User).where(User.username == data.username)):
        raise HTTPException(status_code=409, detail="Username already exists")
    first_account = db.scalar(select(func.count()).select_from(User)) == 0
    user = User(username=data.username, password_hash=password_digest(data.password), is_admin=first_account)
    db.add(user); db.commit(); db.refresh(user)
    return {"access_token": create_token(user.id), "token_type": "bearer", "username": user.username, "is_admin": user.is_admin}


@app.post("/api/auth/login")
def login(data: CredentialsIn, db: Session = Depends(db_session)):
    user = db.scalar(select(User).where(User.username == data.username))
    if user is None or not verify_password(data.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Incorrect username or password")
    return {"access_token": create_token(user.id), "token_type": "bearer", "username": user.username, "is_admin": user.is_admin}


@app.get("/api/me")
def me(user: User = Depends(current_user)):
    return {"id": user.id, "username": user.username, "is_admin": user.is_admin}


@app.get("/api/admin/overview")
def admin_overview(user: User = Depends(current_user), db: Session = Depends(db_session)):
    if not user.is_admin:
        raise HTTPException(status_code=403, detail="Administrator access required")
    users = db.scalars(select(User).order_by(User.created_at)).all()
    user_rows = []
    for account in users:
        count = db.scalar(select(func.count()).select_from(Entry).where(Entry.owner_id == account.id)) or 0
        user_rows.append({"username": account.username, "is_admin": account.is_admin, "created_at": account.created_at, "entry_count": count})
    recent = db.execute(select(Entry, User.username).join(User, Entry.owner_id == User.id).order_by(Entry.updated_at.desc()).limit(12)).all()
    recent_rows = [{"username": owner, "title": entry.title, "kind": entry.kind, "state": entry.state, "updated_at": entry.updated_at} for entry, owner in recent]
    return {
        "service": "ok",
        "database": "SQLite" if DATABASE_URL.startswith("sqlite") else "SQL database",
        "counts": {
            "users": len(users),
            "entries": db.scalar(select(func.count()).select_from(Entry)) or 0,
            "tags": db.scalar(select(func.count()).select_from(Tag)) or 0,
            "movies": db.scalar(select(func.count()).select_from(Entry).where(Entry.kind == "MOVIE")) or 0,
            "series": db.scalar(select(func.count()).select_from(Entry).where(Entry.kind == "SERIES")) or 0,
            "wishlist": db.scalar(select(func.count()).select_from(Entry).where(Entry.state == "WISHLIST")) or 0,
        },
        "users_detail": user_rows,
        "recent_activity": recent_rows,
    }


@app.get("/api/entries")
def list_entries(kind: str | None = None, state: str | None = None, q: str | None = None, user: User = Depends(current_user), db: Session = Depends(db_session)):
    stmt = select(Entry).where(Entry.owner_id == user.id).order_by(Entry.updated_at.desc())
    if kind:
        if kind not in {"MOVIE", "SERIES"}: raise HTTPException(422, "kind must be MOVIE or SERIES")
        stmt = stmt.where(Entry.kind == kind)
    if state:
        if state not in {"WATCHED", "WISHLIST"}: raise HTTPException(422, "state must be WATCHED or WISHLIST")
        stmt = stmt.where(Entry.state == state)
    items = db.scalars(stmt).all()
    if q:
        needle = q.casefold()
        items = [e for e in items if needle in e.title.casefold() or needle in e.review.casefold() or any(needle in t.casefold() for t in json.loads(e.tags))]
    return [entry_out(e) for e in items]


@app.put("/api/library")
def replace_library(data: LibraryIn, user: User = Depends(current_user), db: Session = Depends(db_session)):
    """Atomically save the current user's complete library from the single-device UI."""
    old_titles = {e.title.casefold(): e.catalog_id for e in db.scalars(select(Entry).where(Entry.owner_id == user.id)).all()}
    entries = []
    for x in data.entries:
        title, tags = x.title.strip(), x.tags
        if x.catalog_id:
            x.catalog_id, title, catalog_tags, _ = canonical_entry(x); tags = list(dict.fromkeys(tags + catalog_tags))
        elif title.casefold() not in old_titles:
            try:
                probe = EntryIn(title=title, kind=x.kind, state=x.state, rating=x.rating, review=x.review, watched_date=x.watched_date, tags=x.tags)
                x.catalog_id, title, catalog_tags, _ = canonical_entry(probe); tags = list(dict.fromkeys(tags + catalog_tags))
            except HTTPException:
                raise HTTPException(422, f"'{title}' is not a catalog item; search and select a title first")
        entries.append(Entry(owner_id=user.id, catalog_id=x.catalog_id or old_titles.get(title.casefold()), title=title, kind=x.kind, state=x.state, rating=x.rating if x.state == "WATCHED" else None, review=x.review if x.state == "WATCHED" else "", watched_date=x.watched_date if x.state == "WATCHED" else None, tags=json.dumps(tags, ensure_ascii=False)))
    tags = [Tag(owner_id=user.id, name=x.name.strip(), for_movies=x.for_movies, for_series=x.for_series) for x in data.tags]
    if any(not t.for_movies and not t.for_series for t in data.tags):
        raise HTTPException(422, "Tag must apply to movies or series")
    normalized = [t.name.casefold() for t in data.tags]
    if len(set(normalized)) != len(normalized):
        raise HTTPException(422, "Tag names must be unique")
    db.query(Entry).filter(Entry.owner_id == user.id).delete(synchronize_session=False)
    db.query(Tag).filter(Tag.owner_id == user.id).delete(synchronize_session=False)
    db.add_all(entries + tags)
    db.commit()
    return {"entries": len(entries), "tags": len(tags)}


@app.post("/api/entries", status_code=201)
def create_entry(data: EntryIn, user: User = Depends(current_user), db: Session = Depends(db_session)):
    catalog_id, canonical_title, catalog_tags, _ = canonical_entry(data)
    data.title = canonical_title; data.tags = list(dict.fromkeys(data.tags + catalog_tags))
    entry = Entry(owner_id=user.id, catalog_id=catalog_id, title=data.title.strip(), kind=data.kind, state=data.state, rating=data.rating if data.state == "WATCHED" else None, review=data.review if data.state == "WATCHED" else "", watched_date=data.watched_date if data.state == "WATCHED" else None, tags=json.dumps(data.tags, ensure_ascii=False))
    db.add(entry); db.commit(); db.refresh(entry)
    return entry_out(entry)


@app.patch("/api/entries/{entry_id}")
def update_entry(entry_id: int, data: EntryIn, user: User = Depends(current_user), db: Session = Depends(db_session)):
    entry = db.scalar(select(Entry).where(Entry.id == entry_id, Entry.owner_id == user.id))
    if entry is None: raise HTTPException(404, "Entry not found")
    if data.catalog_id or data.title.strip().casefold() != entry.title.casefold():
        data.catalog_id, canonical_title, catalog_tags, _ = canonical_entry(data); data.title = canonical_title; data.tags = list(dict.fromkeys(data.tags + catalog_tags))
    entry.catalog_id=data.catalog_id or entry.catalog_id; entry.title=data.title.strip(); entry.kind=data.kind; entry.state=data.state; entry.rating=data.rating if data.state == "WATCHED" else None; entry.review=data.review if data.state == "WATCHED" else ""; entry.watched_date=data.watched_date if data.state == "WATCHED" else None; entry.tags=json.dumps(data.tags, ensure_ascii=False)
    db.commit(); db.refresh(entry)
    return entry_out(entry)


@app.delete("/api/entries/{entry_id}", status_code=204)
def delete_entry(entry_id: int, user: User = Depends(current_user), db: Session = Depends(db_session)):
    entry = db.scalar(select(Entry).where(Entry.id == entry_id, Entry.owner_id == user.id))
    if entry is None: raise HTTPException(404, "Entry not found")
    db.delete(entry); db.commit()


@app.get("/api/tags")
def list_tags(user: User = Depends(current_user), db: Session = Depends(db_session)):
    return db.scalars(select(Tag).where(Tag.owner_id == user.id).order_by(Tag.name)).all()


@app.post("/api/tags", status_code=201)
def create_tag(data: TagIn, user: User = Depends(current_user), db: Session = Depends(db_session)):
    if not data.for_movies and not data.for_series: raise HTTPException(422, "Tag must apply to movies or series")
    if db.scalar(select(Tag).where(Tag.owner_id == user.id, Tag.name == data.name.strip())): raise HTTPException(409, "Tag already exists")
    tag=Tag(owner_id=user.id,name=data.name.strip(),for_movies=data.for_movies,for_series=data.for_series);db.add(tag);db.commit();db.refresh(tag);return tag


@app.delete("/api/tags/{tag_id}", status_code=204)
def delete_tag(tag_id: int, user: User = Depends(current_user), db: Session = Depends(db_session)):
    tag=db.scalar(select(Tag).where(Tag.id==tag_id,Tag.owner_id==user.id))
    if tag is None: raise HTTPException(404,"Tag not found")
    db.delete(tag);db.commit()


if os.path.isdir(STATIC_DIR):
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

    @app.get("/admin")
    def admin_page():
        return FileResponse(os.path.join(STATIC_DIR, "admin.html"))

    @app.get("/{path:path}")
    def spa(path: str):
        requested = os.path.abspath(os.path.join(STATIC_DIR, path))
        if requested.startswith(STATIC_DIR) and os.path.isfile(requested):
            return FileResponse(requested)
        return FileResponse(os.path.join(STATIC_DIR, "index.html"))
