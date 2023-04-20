import static Utilities.*

class Config {
    boolean _printRequestHeaders = true
    boolean _printResponseHeaders = true
    boolean _printRequestBody = true
    boolean _printResponseBody = true
    boolean _logResponseBody = false

    Object methodMissing(String name, Object args) {
        Utilities.fatalError("Config does not have a setting named '${name}'.")
    }

    void printRequestHeaders(boolean flag) {
        _printRequestHeaders = flag
    }

    void printResponseHeaders(boolean flag) {
        _printResponseHeaders = flag
    }

    void printRequestBody(boolean flag) {
        _printRequestBody = flag
    }

    void printResponseBody(boolean flag) {
        _printResponseBody = flag
    }

    void logResponseBody(boolean flag) {
        _logResponseBody = flag
    }

    boolean printRequestHeaders() {
        _printRequestHeaders
    }

    boolean printResponseHeaders() {
        _printResponseHeaders
    }

    boolean printRequestBody() {
        _printRequestBody
    }

    boolean printResponseBody() {
        _printResponseBody
    }

    boolean logResponseBody() {
        _logResponseBody
    }
}
