"""Errors as RFC 9457 problem documents with a stable machine code (02 §5.1)."""

from http import HTTPStatus

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException

_CODES = {404: "not_found", 405: "method_not_allowed"}


class Problem(Exception):
    def __init__(self, status: int, code: str, detail: str, headers: dict[str, str] | None = None):
        self.status, self.code, self.detail, self.headers = status, code, detail, headers


def problem_response(
    status: int, code: str, detail: str, headers: dict[str, str] | None = None
) -> JSONResponse:
    body = {
        "type": "about:blank",
        "title": HTTPStatus(status).phrase,
        "status": status,
        "code": code,
        "detail": detail,
    }
    return JSONResponse(body, status, headers, media_type="application/problem+json")


def install(app: FastAPI) -> None:
    @app.exception_handler(Problem)
    async def _problem(request: Request, exc: Problem) -> JSONResponse:
        return problem_response(exc.status, exc.code, exc.detail, exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _invalid(request: Request, exc: RequestValidationError) -> JSONResponse:
        error = exc.errors()[0]
        field = ".".join(str(part) for part in error["loc"][1:]) or "body"
        return problem_response(422, "validation_failed", f"{field}: {error['msg']}.")

    @app.exception_handler(HTTPException)
    async def _http(request: Request, exc: HTTPException) -> JSONResponse:
        phrase = HTTPStatus(exc.status_code).phrase
        code = _CODES.get(exc.status_code, phrase.lower().replace(" ", "_"))
        return problem_response(exc.status_code, code, str(exc.detail), exc.headers)
