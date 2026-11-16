import os

os.environ.setdefault("DATABASE_URL", "sqlite:///./cinelog-test.db")
os.environ.setdefault("JWT_SECRET", "test-only-secret-with-more-than-thirty-two-characters")

from fastapi.testclient import TestClient
from app.main import app, Base, engine


def test_auth_and_user_scoped_data():
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    with TestClient(app) as client:
        assert client.get("/health").json()["status"] == "ok"
        assert client.get("/api/entries").status_code == 401

        register = client.post("/api/auth/register", json={"username": "alice", "password": "correct-horse-123"})
        assert register.status_code == 201
        alice = {"Authorization": f"Bearer {register.json()['access_token']}"}

        created = client.post("/api/entries", headers=alice, json={
            "title": "Arrival", "kind": "MOVIE", "state": "WATCHED", "rating": 5,
            "review": "A thoughtful science-fiction film.", "watched_date": "2026-11-16", "tags": ["Sci-fi"]
        })
        assert created.status_code == 201
        assert created.json()["title"] == "Arrival"

        listed = client.get("/api/entries?kind=MOVIE&state=WATCHED&q=sci-fi", headers=alice)
        assert len(listed.json()) == 1
        assert listed.json()[0]["rating"] == 5

        tag = client.post("/api/tags", headers=alice, json={"name": "Favorites", "for_movies": True, "for_series": False})
        assert tag.status_code == 201
        assert tag.json()["for_series"] is False

        login = client.post("/api/auth/login", json={"username": "alice", "password": "correct-horse-123"})
        assert login.status_code == 200
        assert client.post("/api/auth/login", json={"username": "alice", "password": "wrong-password"}).status_code == 401

        bob = client.post("/api/auth/register", json={"username": "bob", "password": "another-password-456"})
        bob_headers = {"Authorization": f"Bearer {bob.json()['access_token']}"}
        assert client.get("/api/entries", headers=bob_headers).json() == []
        assert client.get("/api/tags", headers=bob_headers).json() == []

    Base.metadata.drop_all(engine)
