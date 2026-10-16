"""Password and bearer credentials. Plaintext passwords/tokens are never persisted."""
import hashlib
import hmac
import secrets


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def password_hash(password: str) -> str:
    salt = secrets.token_hex(16)
    derived = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1)
    return salt + ":" + derived.hex()


def password_matches(password: str, encoded: str) -> bool:
    salt, expected = encoded.split(":")
    actual = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()
    return hmac.compare_digest(actual, expected)


# Equal-cost verification for a missing username.
DUMMY_PASSWORD = password_hash(secrets.token_urlsafe(32))
