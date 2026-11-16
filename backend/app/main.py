import base64
import hashlib
import hmac
import json
import os
import secrets
from datetime import datetime, timedelta, timezone
from typing import Generator

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker


DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./cinelog.db")
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
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class Entry(Base):
    __tablename__ = "entries"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
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
app = FastAPI(title="CineLog API", version="1.0.0", description="Private, user-scoped CineLog API")
app.add_middleware(CORSMiddleware, allow_origins=os.getenv("CORS_ORIGINS", "*").split(","), allow_credentials=False, allow_methods=["GET", "POST", "PATCH", "DELETE"], allow_headers=["Authorization", "Content-Type"])
bearer = HTTPBearer(auto_error=False)


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
    password: str = Field(min_length=10, max_length=128)


class EntryIn(BaseModel):
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


def entry_out(entry: Entry) -> dict:
    return {"id": entry.id, "title": entry.title, "kind": entry.kind, "state": entry.state, "rating": entry.rating, "review": entry.review, "watched_date": entry.watched_date, "tags": json.loads(entry.tags), "created_at": entry.created_at, "updated_at": entry.updated_at}


@app.get("/health")
def health():
    return {"status": "ok", "service": "cinelog-api"}


@app.post("/api/auth/register", status_code=201)
def register(data: CredentialsIn, db: Session = Depends(db_session)):
    if db.scalar(select(User).where(User.username == data.username)):
        raise HTTPException(status_code=409, detail="Username already exists")
    user = User(username=data.username, password_hash=password_digest(data.password))
    db.add(user); db.commit(); db.refresh(user)
    return {"access_token": create_token(user.id), "token_type": "bearer", "username": user.username}


@app.post("/api/auth/login")
def login(data: CredentialsIn, db: Session = Depends(db_session)):
    user = db.scalar(select(User).where(User.username == data.username))
    if user is None or not verify_password(data.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Incorrect username or password")
    return {"access_token": create_token(user.id), "token_type": "bearer", "username": user.username}


@app.get("/api/me")
def me(user: User = Depends(current_user)):
    return {"id": user.id, "username": user.username}


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


@app.post("/api/entries", status_code=201)
def create_entry(data: EntryIn, user: User = Depends(current_user), db: Session = Depends(db_session)):
    entry = Entry(owner_id=user.id, title=data.title.strip(), kind=data.kind, state=data.state, rating=data.rating if data.state == "WATCHED" else None, review=data.review if data.state == "WATCHED" else "", watched_date=data.watched_date if data.state == "WATCHED" else None, tags=json.dumps(data.tags, ensure_ascii=False))
    db.add(entry); db.commit(); db.refresh(entry)
    return entry_out(entry)


@app.patch("/api/entries/{entry_id}")
def update_entry(entry_id: int, data: EntryIn, user: User = Depends(current_user), db: Session = Depends(db_session)):
    entry = db.scalar(select(Entry).where(Entry.id == entry_id, Entry.owner_id == user.id))
    if entry is None: raise HTTPException(404, "Entry not found")
    entry.title=data.title.strip(); entry.kind=data.kind; entry.state=data.state; entry.rating=data.rating if data.state == "WATCHED" else None; entry.review=data.review if data.state == "WATCHED" else ""; entry.watched_date=data.watched_date if data.state == "WATCHED" else None; entry.tags=json.dumps(data.tags, ensure_ascii=False)
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
