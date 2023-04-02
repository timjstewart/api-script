import groovy.json.JsonSlurper
import groovy.transform.TypeChecked

enum Method {
    HEAD, GET, DELETE, POST, PUT, PATCH, OPTIONS
}


@TypeChecked
class ApiScriptDSL {
    public static RequestDSL DELETE(String url, Closure c) {return request(Method.DELETE, url, c)}
    public static RequestDSL GET(String url, Closure c) {return request(Method.GET, url, c)}
    public static RequestDSL HEAD(String url, Closure c) {return request(Method.HEAD, url, c)}
    public static RequestDSL OPTIONS(String url, Closure c) {return request(Method.OPTIONS, url, c)}
    public static RequestDSL PATCH(String url, Closure c) {return request(Method.PATCH, url, c)}
    public static RequestDSL POST(String url, Closure c) {return request(Method.POST, url, c)}
    public static RequestDSL PUT(String url, Closure c) {return request(Method.PUT, url, c)}

    private static RequestDSL request(Method method,
                                      String url,
                                      Closure c) {
        var dsl = new RequestDSL(method, url)
        c.delegate = dsl
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()
        return dsl
    }

    private static void checkDependencies(RequestDSL[] requests) {
        String[] requiredValues = []
        requests.collate(2, 1).each {
            requiredValues += it[0].providers.keySet()
            it[0].requiredValues.each {
                if (!(it in requiredValues)) {
                    throw new ApiScriptException("'${it}' was not found in provided values ${requiredValues}")
                }
            }
        }
    }

    static void send(RequestDSL... requests) {
        checkDependencies(requests)
        var provider = new MultiRequestProvider()
        requests.each {
            Response response = it.send(provider)
            provider.addResponse(response)
            println(response.statusCode)
        }
    }
}

class RequestDSL {
    final Method method
    String url
    final Map<String, String> headers = [:]
    final List<Tuple2<String, String>> params = new ArrayList<>()
    private String body
    Map<String, Provider> providers = [:]
    List<String> requiredValues = []

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

    Response send(Provider provider) {
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
            // The first line of the response is represented as a MapEntry with no key.
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

    static Object propertyMissing(String name) {
        return "${name}"
    }

    boolean canProvide(String name) {
        return name in providers
    }

    void requires(String name) {
        requiredValues << name
    }

    Object provides(String valueName) {
        [ from: { sourceType ->
            return new ProviderDispatch(this, valueName, sourceType)
        }]
    }
}

/**
 * Selects the correct Provider based on the provider type
 */
@TypeChecked
class ProviderDispatch {
    RequestDSL request
    String valueName
    String sourceType

    ProviderDispatch(RequestDSL request,
                     String valueName,
                     String sourceType) {
        this.request = request
        this.valueName = valueName
        this.sourceType = sourceType
    }

    void propertyMissing(String sourceSpec) {
        switch(sourceType) {
            case "header":
                request.providers[valueName] =
                    new HeaderSource(sourceSpec)
                break
            case "body":
                request.providers[valueName] =
                    new BodySource(sourceSpec)
                break
            default:
                throw new ApiScriptException(
                    "unknown source '${sourceType}'")
        }
    }
}

/**
 * Provides a value from a Response (e.g. a header value,
 * JSON object, etc.)
 */
@TypeChecked
abstract class Provider {
    Object provide(Response response) {
        return null
    }
}

@TypeChecked
class MultiRequestProvider extends Provider {
    List<Response> responses = []
    void addResponse(Response response) {
        this.responses.add(response)
    }
}

@TypeChecked
class HeaderSource extends Provider {
    String headerName
    HeaderSource(String headerName) {
        this.headerName = headerName
    }
}

@TypeChecked
class BodySource extends Provider {
    String jsonPath
    BodySource(String jsonPath) {
        this.jsonPath = jsonPath
    }
}

@TypeChecked
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

@TypeChecked
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

@TypeChecked
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

@TypeChecked
class Utilities {
    static headersToString(Map<String, String> headers) {
        headers.collect {"${it.key}: ${it.value}"}.join("\n")
    }

    static String paramsToString(List<Tuple2<String,String>> params) {
        "?" + params.collect {"${it.V1}=${it.V2}"}.join("&")
    }
}

@TypeChecked
class ApiScriptException extends Exception {
    ApiScriptException(String what) {
        super(what)
    }
}
