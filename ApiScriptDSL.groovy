import groovy.json.JsonSlurper

class ApiScriptDSL {
    public static RequestDSL delete(String url, Closure c) {return request(Method.DELETE, url, c)}
    public static RequestDSL get(String url, Closure c) {return request(Method.GET, url, c)}
    public static RequestDSL head(String url, Closure c) {return request(Method.HEAD, url, c)}
    public static RequestDSL options(String url, Closure c) {return request(Method.OPTIONS, url, c)}
    public static RequestDSL patch(String url, Closure c) {return request(Method.PATCH, url, c)}
    public static RequestDSL post(String url, Closure c) {return request(Method.POST, url, c)}
    public static RequestDSL put(String url, Closure c) {return request(Method.PUT, url, c)}

    private static RequestDSL request(Method method,
                                      String url,
                                      Closure c) {
        var dsl = new RequestDSL(method, url)
        c.delegate = dsl
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()
        return dsl
    }

    static void send(RequestDSL... requests) {
        requests.each {
            Response response = it.send()
            if (response.isJson()) {
                println(response.toJson())
            } else {
                println(response)
            }
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

    RequestDSL(Method method, String url) {
        this.method = method
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
        Map<String, String> headers = [:]

        connection.getHeaderFields().each {
            if (it.key) {
                headers[it.key.toLowerCase()] = it.value[0]
            }
        }

        return new Response(connection.responseCode,
                            headers,
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
    String contentType

    Response(Integer statusCode,
             Map<String, String> headers,
             String body) {
        this.statusCode = statusCode
        this.headers = headers
        this.body = body
        this.contentType = this.headers.'content-type'
    }

    Object toJson() {
        if (body) {
            return new JsonSlurper().parseText(body)
        }
        return null
    }

    boolean isJson() {
        return contentType == "application/json"
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
