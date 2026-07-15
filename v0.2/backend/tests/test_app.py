from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_health_is_available_without_database_or_oauth() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "healthy"}


def test_root_identifies_the_legacy_python_api() -> None:
    response = client.get("/")

    assert response.status_code == 200
    assert response.json()["version"] == "0.1.0"
