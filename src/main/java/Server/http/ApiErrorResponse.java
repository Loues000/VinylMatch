package Server.http;

public record ApiErrorResponse(ApiError error) {

    public record ApiError(String code, int status, String message) {}
}

