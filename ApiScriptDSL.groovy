@Grab(group="org.fusesource.jansi",
      module="jansi",
      version="2.4.0")

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.regex.Pattern

import groovy.json.JsonSlurper
import groovy.transform.TypeChecked

import org.fusesource.jansi.AnsiConsole
import org.fusesource.jansi.Ansi.Color
import static org.fusesource.jansi.Ansi.*
import static org.fusesource.jansi.Ansi.Color.*

enum Method {
    HEAD, GET, DELETE, POST, PUT, PATCH, OPTIONS
}

@TypeChecked
class ApiScriptDSL extends Style {
    private static HttpClient httpClient = HttpClient .newBuilder() .build()

    public static RequestDSL DELETE(String url, Closure c = null) {return request(Method.DELETE, url, c)}
    public static RequestDSL GET(String url, Closure c = null) {return request(Method.GET, url, c)}
    public static RequestDSL HEAD(String url, Closure c = null) {return request(Method.HEAD, url, c)}
    public static RequestDSL OPTIONS(String url, Closure c = null) {return request(Method.OPTIONS, url, c)}
    public static RequestDSL PATCH(String url, Closure c = null) {return request(Method.PATCH, url, c)}
    public static RequestDSL POST(String url, Closure c = null) {return request(Method.POST, url, c)}
    public static RequestDSL PUT(String url, Closure c = null) {return request(Method.PUT, url, c)}

    private static RequestDSL request(Method method,
                                      String url,
                                      Closure c) {

        initTerminal()
        var dsl = new RequestDSL(method, url)
        try {
            if (c) {
                c.delegate = dsl
                c.resolveStrategy = Closure.DELEGATE_ONLY
                c.call()
            }
            return dsl
        } catch (SyntaxError | EvaluationError ex) {
            inColor YELLOW, {
                println(ex.getMessage())
                System.exit(0)
            }
        }
    }

    private static void checkDependencies(RequestDSL[] requests) {
        var providedValues = new HashSet<String>()

        requests.collate(2, 1).each {
            var providingRequest = it[0]
            var requiringRequest = it[1]
            if (!requiringRequest)
               return

            providedValues.addAll(providingRequest.providers.keySet())

            requiringRequest.valueReferences().each {
                if (!(it in providedValues)) {
                    throw new ApiScriptException(
                        "'${it}' was not found in provided values: ${providedValues.join(', ')}")
                }
            }
        }
    }

    static void send(RequestDSL... requests) {
        try {
            checkDependencies(requests)
            var dictionary = new Dictionary()
            requests.each {
                Response response = it.send(dictionary)
                dictionary.addSource(new DictionarySource(it, response))
                println()
            }
        } catch (ApiScriptException ex) {
            System.err.println("error: ${ex.getMessage()}")
        }
    }

    private static void initTerminal() {
        AnsiConsole.systemInstall()
        print(ansi().a(Attribute.RESET))
    }
}

class Style {
    static void inColor(Color color, Closure c) {
        print(ansi().fg(color))
        c.call()
        print(ansi().a(Attribute.RESET))
    }

    static void inBold(Closure c) {
        print(ansi().a(Attribute.INTENSITY_BOLD))
        c.call()
        print(ansi().a(Attribute.RESET))
    }

    static void inThin(Closure c) {
        print(ansi().a(Attribute.INTENSITY_FAINT))
        c.call()
        print(ansi().a(Attribute.RESET))
    }
}

class RequestDSL extends Style {
    final Method method
    String url
    final List<Tuple2<String, String>> params = new ArrayList<>()
    final Map<String, String> headers = [:]
    private String body

    Map<String, ValueLocator> providers = [:]

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

    Response send(Dictionary dictionary) {
        def url = dictionary.interpolate(url + Utilities.paramsToString(params))
        inColor GREEN, {println("⬆️ ${method} ${url}")}

        try {
            HttpRequest request = buildRequest(dictionary);
            Utilities.timed "Response Latency", {
                HttpResponse response = ApiScriptDSL.httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                createResponse(response)
            }
        } catch (IOException ex) {
            println("I/O Error")
        }
    }

    private HttpRequest buildRequest(Dictionary dictionary) {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(2))

        headers.each {
            var name = it.key.toLowerCase()
            var value = dictionary.interpolate(it.value)
            inBold {println("  ${name}: ${value}")}
            builder.header(name, value)
        }

        if (body) {
            var actualBody = dictionary.interpolate(body)
            inThin {println(actualBody) }
            builder.method(method.toString(),
                           HttpRequest.BodyPublishers.ofString(body))
        }

        return builder.build()
    }

    private static Response createResponse(HttpResponse response) {
        Map<String, String> headers = [:]

        response.headers().map().each {
            // The first line of the response is represented as a MapEntry with no key.
            if (it.key) {
                headers[it.key] = it.value[0]
            }
        }

        var result = new Response(response.statusCode(),
                                  headers,
                                  response.body())

        var succeeded = response.statusCode() in (200 .. 302) 

        inColor succeeded ? GREEN : RED, {
            println("⬇️ Status: ${result.statusCode}")
        }

        inBold {
            result.headers.each {
                println("  ${it.key}: ${it.value}")
            }
        }
        println()
        println(result.body);
        result
    }

    @Override
    String toString() {
        return """${method} ${url}${Utilities.paramsToString(params)}
${Utilities.headersToString(headers)}
${body}
"""
    }

    Object methodMissing(String name, Object args) {
        throw new SyntaxError(
            "unexpected '${name}' while defining request.")
    }

    boolean canProvide(String name) {
        return name in providers
    }

    String provide(Response response, String valueName) {
        providers[valueName].provide(response)
    }

    Object provides(String valueName) {
        final RequestDSL request = this
        return new HashMap<String, Object>() {
            Object from(String sourceType) {
                return new ProviderDispatch(
                    request, valueName, sourceType)
            }
        }
    }

    static Object propertyMissing(String name) {
        if (name in ["header", "json"])
            return "${name}"
        throw new SyntaxError("Unexpected '${name}'")
    }

    Set<String> valueReferences() {
        var refs = new HashSet<String>()
        refs.addAll(Utilities.findValueReferences(url))
        refs
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
                    new InHeader(sourceSpec)
                break

            case "json":
                request.providers[valueName] =
                    new InJson(sourceSpec)
                break

            default:
                throw new ApiScriptException(
                    "unknown source '${sourceType}'")
        }
    }
}

class DictionarySource {
    RequestDSL request
    Response response

    DictionarySource(RequestDSL request, Response response) {
        this.request = request
        this.response = response
    }

    String getValue(String valueName) {
        var provider = request.providers[valueName]
        if (provider) {
            return provider.extractValue(response)
        }
    }

    @Override
    String toString() {
        "${request.url} - ${request.providers.keySet()}"
    }
}

/**
 * Provides a value from a Response (e.g. a header value,
 * JSON object, etc.)
 */
class Dictionary {
    List<DictionarySource> sources = []

    void addSource(DictionarySource source) {
        sources << source
    }

    String getValue(String valueName) {
        for (source in sources.reverse()) {
            var value = source.getValue(valueName)
            if (value) {
                return value
            }
        }
        throw new ApiScriptException("could not find value '${valueName}' in sources: ${sources.reverse()}")
    }

    String interpolate(String text) {
        Utilities.replaceValueReferences(text, {m ->
            var valueName = m[1]
            getValue(valueName)
        })
    }
}

abstract class ValueLocator {
    abstract String extractValue(Response response)
}

@TypeChecked
class InHeader extends ValueLocator {
    String headerName
    InHeader(String headerName) {
        this.headerName = headerName
    }

    String extractValue(Response response) {
        response.headers[headerName]
    }
}

@TypeChecked
class InJson extends ValueLocator {
    String jsonPath
    InJson(String jsonPath) {
        this.jsonPath = jsonPath
    }

    String extractValue(Response response) {
        if (response.isJson()) {
            try {
                var json = response.toJson()
                var path = jsonPath.split('\\.').toList()
                extractJsonValue(path, json)
            } catch (ApiScriptException ex) {
                throw new ApiScriptException("could not find JSON value '${jsonPath}'.  ${ex.getMessage()}", ex)
            }
        } else {
            throw new ApiScriptException("could not find JSON value '${jsonPath}' in non-JSON body: '${response.toString()}'")
        }
    }

    private String extractJsonValue(List<String> path, Object json) {
        if (path.isEmpty()) {
            if (json instanceof String || json instanceof Float || json instanceof Boolean) {
                return json.toString()
            } else {
                throw new ApiScriptException("found non-scalar at path '${json}'")
            }
        } else {
            var head = path.head()
            if (json instanceof Map) {
                var obj = json as Map<String, Object>
                if (head in obj) {
                    extractJsonValue(path.tail(), obj[head]) 
                } else {
                    throw new ApiScriptException("key '${head}' not found in json: '${json}'")
                }
            } else {
                throw new ApiScriptException("key '${head}' not found in non-object: '${json}'")
            }
        }
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

class BaseDSL {
    String env(String envVarName, String defaultValue = null) {
        Utilities.getEnvVar(envVarName, defaultValue)
    }
}

@TypeChecked
class HeaderDSL extends BaseDSL {
    String name
    RequestDSL request

    HeaderDSL(RequestDSL request, String name) {
        this.request = request
        this.name = name
    }

    void propertyMissing(String value) {
        request.headers[name] = value
    }

    Object methodMissing(String name, Object args) {
        throw new SyntaxError("Unexepcted '${name}' while defining header")
    }
}

@TypeChecked
class ParamDSL extends BaseDSL {
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
class Utilities extends Style {
    final static Pattern VALUE_NAME_REGEX = ~/\{\{([^}]*)\}\}/

    static headersToString(Map<String, String> headers) {
        headers.collect {"${it.key}: ${it.value}"}.join("\n")
    }

    static String paramsToString(List<Tuple2<String,String>> params) {
        var result = params.collect {"${it.V1}=${it.V2}"}.join("&")
        result ? "?" + result : ""
    }

    static Set<String> findValueReferences(String text) {
        text.findAll(VALUE_NAME_REGEX).toSet()
    }

    static String replaceValueReferences(String text, Closure c) {
        text.replaceAll(VALUE_NAME_REGEX, c)
    }

    static String getEnvVar(String name, String defaultValue) {
        String value = System.getenv(name)
        if (!value) {
            if (defaultValue) {
                println("Environment variable '${name}' not set.  Using default value.")
                return defaultValue
            } else {
                throw new EvaluationError("Environment Variable '${name}' not set.")
            }
        } else {
            println("Environment variable '${name}' found.")
            value
        }
    }

    static Object timed(String operation, Closure c) {
        def startTime = new Date().getTime()
        var result = c.call()
        def stopTime = new Date().getTime()
        inColor(BLUE) {
            println("⏱: ${operation}: ${stopTime - startTime} milliseconds")
        }
        result
    }
}

@TypeChecked
class ApiScriptException extends Exception {
    ApiScriptException(String what) {
        super(what)
    }

    ApiScriptException(String what, Exception cause) {
        super(what, cause)
    }
}

class SyntaxError extends ApiScriptException {
    SyntaxError(String what) {
        super(what)
    }
}

class EvaluationError extends ApiScriptException {
    EvaluationError(String what) {
        super(what)
    }
}
