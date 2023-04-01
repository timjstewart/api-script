class ApiScriptDSL {
    public static RequestDSL delete(Closure c) {return request(Method.DELETE, c)}
    public static RequestDSL get(Closure c) {return request(Method.GET, c)}
    public static RequestDSL head(Closure c) {return request(Method.HEAD, c)}
    public static RequestDSL options(Closure c) {return request(Method.OPTIONS, c)}
    public static RequestDSL patch(Closure c) {return request(Method.PATCH, c)}
    public static RequestDSL post(Closure c) {return request(Method.POST, c)}
    public static RequestDSL put(Closure c) {return request(Method.PUT, c)}

    private static RequestDSL request(Method method, Closure c) {
        var dsl = new RequestDSL(method)
        c.delegate = dsl
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()
        return dsl
    }

    static void send(RequestDSL... requests) {
        requests.each {
            Response response = it.send()
            println(response)
        }
    }
}

enum Method {
    HEAD, GET, DELETE, POST, PUT, PATCH, OPTIONS
}

class RequestDSL {
    final Method method
    String url
    final Map<String, String> headers = [:]
    final List<Tuple2<String, String>> params = new ArrayList<>()
    private String body

    RequestDSL(Method method) {
        this.method = method
    }

    void url(String url) {
        this.url = url
    }

    HeaderDSL header(String name) {
        return new HeaderDSL(this, name)
    }

    ParamDSL param(String name) {
        return new ParamDSL(this, name)
    }

    void body(String body) {
        this.body = body
    }

    Response send() {
        println(this)
        def baseUrl = new URL(url + Utilities.paramsToString(params))
        def connection = baseUrl.openConnection()
        connection.requestMethod = method.toString()
        headers.each {
            connection.setRequestProperty(it.key, it.value)
        }
        if (body) {
            connection.setDoOutput(true)
            connection.getOutputStream().write(body.getBytes("UTF-8"));
        }
        return new Response(connection.responseCode,
                            connection.getHeaderFields(),
                            connection.getInputStream().getText())
    }

    @Override
    String toString() {
        return """${method} ${url}${Utilities.paramsToString(params)}
${Utilities.headersToString(headers)}
${body}
"""
    }
}

class Response {
    String body
    Integer statusCode
    final Map<String, String> headers = [:]

    Response(Integer statusCode,
             Map<String, String> headers,
             String body) {
        this.statusCode = statusCode
        this.headers = headers
        this.body = body
    }

    @Override
    String toString() {
        return """Status Code: ${statusCode}
Headers: ${Utilities.headersToString(headers)}
Body: ${body}"""
    }
}

class HeaderDSL {
    String name
    RequestDSL request
    HeaderDSL(RequestDSL request, String name) {
        this.request = request
        this.name = name
    }
    void propertyMissing(String value) {
        request.headers[name] = value
    }
}

class ParamDSL {
    String name
    RequestDSL request
    ParamDSL(RequestDSL request, String name) {
        this.request = request
        this.name = name
    }
    void propertyMissing(String value) {
        request.params << new Tuple2(name, value)
    }
}

class Utilities {
    static headersToString(Map<String, String> headers) {
        headers.collect {"${it.key}: ${it.value}"}.join("\n")
    }

    static String paramsToString(List<Tuple2<String,String>> params) {
        "?" + params.collect {"${it.V1}=${it.V2}"}.join("&")
    }
}
