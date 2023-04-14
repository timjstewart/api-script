import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.time.Duration
import java.util.regex.Pattern
import java.util.regex.Matcher

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.TypeChecked

import org.fusesource.jansi.Ansi.Color
import org.fusesource.jansi.AnsiConsole
import static org.fusesource.jansi.Ansi.*
import static org.fusesource.jansi.Ansi.Color.*

enum Method {
    HEAD,
    GET,
    DELETE,
    POST,
    PUT,
    PATCH,
    OPTIONS
}

@TypeChecked
class ApiScript {
    static void script(@DelegatesTo(Statements) Closure<Void> c) {
        Terminal.init()

        var args = getArguments()

        var statements = new Statements()
        c.delegate = statements
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()

        if (args.length == 0) {
            statements.printAvailableGroups()
        } else {
            var groupName = args[0]
            if (groupName in statements.groups.keySet()) {
                statements.run(groupName)
            } else {
                println("Unknown group '${groupName}'")
                statements.printAvailableGroups()
            }
        }

        Terminal.close()
    }

    private static String[] getArguments() {
        ProcessHandle.current().info().arguments()
            .orElse([] as String[]).toList()
            .dropWhile{!it.endsWith(".groovy")}
            .drop(1)
    }
}

@TypeChecked
class Terminal {
    static void init() {
        AnsiConsole.systemInstall()
        print(ansi().a(Attribute.RESET))
    }

    static void close() {
        AnsiConsole.systemUninstall()
        print(ansi().a(Attribute.RESET))
    }
}

@TypeChecked
class Statements implements HasStyle {

    private Map<String, List<RequestDSL>> groups = [:]
    private ConfigDSL _config = new ConfigDSL()

    RequestDSL DELETE(String url, Closure<RequestDSL> c = null) {return request(Method.DELETE, url, c)}
    RequestDSL GET(String url, Closure<RequestDSL> c = null) {return request(Method.GET, url, c)}
    RequestDSL HEAD(String url, Closure<RequestDSL> c = null) {return request(Method.HEAD, url, c)}
    RequestDSL OPTIONS(String url, Closure<RequestDSL> c = null) {return request(Method.OPTIONS, url, c)}
    RequestDSL PATCH(String url, Closure<RequestDSL> c = null) {return request(Method.PATCH, url, c)}
    RequestDSL POST(String url, Closure<RequestDSL> c = null) {return request(Method.POST, url, c)}
    RequestDSL PUT(String url, Closure<RequestDSL> c = null) {return request(Method.PUT, url, c)}

    void printAvailableGroups() {
        println("Available groups:")
        groups.keySet().each {
            println(" - ${it}")
        }
    }

    String env(String envVarName, String defaultValue = null) {
        try {
            Utilities.getEnvVar(envVarName, defaultValue)
        } catch (ApiScriptException ex) {
            Utilities.fatalError(ex.getMessage())
        }
    }

    void group(String name, List<RequestDSL> requests) {
        groups[name] = requests
    }

    void run(String groupName) {
        if (groupName in groups) {
            send(groups[groupName])
        }
    }

    void send(List<RequestDSL> requests) {
        try {
            checkDependencies(requests)
            final var dictionary = new Dictionary()
            requests.each {
                final var tokenName = it.tokenSource?.tokenName
                final var tokenRequest = it.tokenSource?.request
                if (tokenName && !dictionary.hasValue(tokenName)) {
                    final Response tokenResponse = tokenRequest.sendRequest(dictionary)
                    dictionary.addSource(new DictionarySource(tokenRequest, tokenResponse))

                    if(!dictionary.hasValue(tokenName)) {
                        throw new EvaluationError(
                            "Could not acquire token named '${tokenName}' from " +
                            "tokenSource request '${tokenRequest.url}'.")
                    }
                }
                final Response response = it.sendRequest(dictionary)
                dictionary.addSource(new DictionarySource(it, response))
                println()
            }
        } catch (ApiScriptException ex) {
            Utilities.fatalError(ex.getMessage())
        }
    }

    void config(@DelegatesTo(ConfigDSL) Closure<Void> c) {
        c.delegate = _config
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()
    }

    private static void checkDependencies(List<RequestDSL> requests) {
        var providedValues = new HashSet<String>()

        requests.collate(2, 1).each {
            var providingRequest = it[0]
            var requiringRequest = it[1]

            if (!requiringRequest)
                return

            providedValues.addAll(
                providingRequest.providers.keySet()
            )

            if (requiringRequest.tokenSource) {
                providedValues.addAll(
                    requiringRequest.tokenSource.request.providers.keySet()
                )
            }

            requiringRequest.valueReferences().each {
                if (!(it in providedValues)) {
                    throw new ApiScriptException(
                        "'${it}' was not found in provided values: ${providedValues.join(', ')}")
                }
            }
        }
    }

    private RequestDSL request(
        Method method,
        String url,
        @DelegatesTo(RequestDSL) Closure<RequestDSL> c) {
        var dsl = new RequestDSL(_config, method, url)
        try {
            if (c) {
                c.delegate = dsl
                c.resolveStrategy = Closure.DELEGATE_ONLY
                c.call()
            }
            return dsl
        } catch (SyntaxError | EvaluationError ex) {
            Utilities.fatalError(ex.getMessage())
        }
    }
}

class ConfigDSL {
    boolean _printRequestHeaders = true
    boolean _printResponseHeaders = true
    boolean _printRequestBody = true
    boolean _printResponseBody = true

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
}

trait HasStyle {
    static void inColor(Color color, Closure<Void> c) {
        print(ansi().fg(color))
        c.call()
        print(ansi().a(Attribute.RESET))
    }

    static void inBold(Closure c) {
        print(ansi().a(Attribute.INTENSITY_BOLD))
        c.call()
        print(ansi().a(Attribute.RESET))
    }

    static void inSubtle(Closure c) {
        print(ansi().a(Attribute.INTENSITY_FAINT))
        c.call()
        print(ansi().a(Attribute.RESET))
    }
}

class RequestDSL implements HasStyle {
    private static HttpClient httpClient = HttpClient.newBuilder() .build()

    private final ConfigDSL config
    private final Method method
    private final String url

    private final List<Tuple2<String, String>> params = new ArrayList<>()
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
    private final Map<String, Provider> providers = [:]

    private String body
    private TokenSource tokenSource

    RequestDSL(ConfigDSL config, Method method, String url) {
        this.config = config
        this.method = method
        this.url = url
    }

    HeaderDSL header(String name) {
        new HeaderDSL(this, name)
    }

    ParamDSL param(String name) {
        new ParamDSL(this, name)
    }

    void body(String body) {
        this.body = body
    }

    Provider getProvider(String valueName) {
        return providers[valueName]
    }

    String describeProviders() {
        "${url} - ${providers.keySet()}"
    }

    TokenSource tokenSource(RequestDSL request) {
        TokenSource source = new TokenSource(request)
        tokenSource = source
        return source
    }

    Response sendRequest(Dictionary dictionary) {
        var interpolatedParams = params.collect {
            new Tuple2(it.V1, dictionary.interpolate(it.V2))
        }

        final def unencodedUrl = url + Utilities.paramsToString(interpolatedParams, false)

        inColor GREEN, {
            println(">>> ${method} ${unencodedUrl}")
        }

        HttpRequest request = buildRequest(dictionary);
        HttpResponse response = null
        Utilities.timed "\nResponse Latency", {
            response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            )
        }
        createResponse(response)
    }

    private HttpRequest buildRequest(Dictionary dictionary) {
        var interpolatedParams = params.collect {
            new Tuple2(it.V1, dictionary.interpolate(it.V2))
        }

        final String fullUrl = "${url}${Utilities.paramsToString(interpolatedParams)}"

        var builder = HttpRequest.newBuilder()
            .uri(URI.create(fullUrl))
            .timeout(Duration.ofMinutes(2))

        headers.each {
            var name = it.key
            var value = dictionary.interpolate(it.value)
            if (config.printRequestHeaders()) {
                inBold {println("  ${name}: ${value}")}
            }
            builder.header(name, value)
        }

        var actualBody = ""

        if (body) {
            actualBody = dictionary.interpolate(body)

            if (config.printRequestBody()) {
                inSubtle {
                    println()
                    println(
                        Utilities.formatBodyText(actualBody,
                                                 headers['content-type']))
                    println()
                }
            }
        }

        builder.method(method.toString(),
                       HttpRequest.BodyPublishers.ofString(actualBody))

        return builder.build()
    }

    private Response createResponse(HttpResponse response) {
        Map<String, String> headers = [:]

        response.headers().map().each {
            // The first line of the response is represented as a MapEntry with
            // no key.
            if (it.key) {
                headers[it.key] = it.value[0]
            }
        }

        var result = new Response(response.statusCode(),
                                  headers,
                                  response.body() as String)

        inColor result.succeeded() ? GREEN : RED, {
            println("<<< Status: ${result.statusCode}")
        }

        if (config.printResponseHeaders()) {
            inBold {
                result.headers.each {
                    println("  ${it.key}: ${it.value}")
                }
            }
            println()
        }

        if (config.printResponseBody()) {
            inSubtle {
                println(result.formattedBody())
            }
        }

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
        name in providers
    }

    String provide(Response response, String valueName) {
        providers[valueName].provideValueFrom(response)
    }

    Object provides(String valueName) {
        final RequestDSL request = this
        new HashMap<String, Object>() {
            Object from(String sourceType) {
                if (sourceType == 'responseBody') {
                    request.providers[valueName] = new InBody()
                } else {
                    return new ProviderDispatch(
                        request, valueName, sourceType)
                }
            }
        }
    }

    static Object propertyMissing(String name) {
        // List of places where values can be retrieved from
        if (name in ["header", "json", "responseBody"])
            return "${name}"
        throw new SyntaxError("Unexpected source '${name}'")
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
    private final RequestDSL request
    private final String valueName
    private final String sourceType

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
        var provider = request.getProvider(valueName)
        if (provider) {
             provider.provideValueFrom(response)
        }
    }

    @Override
    String toString() {
        request.describeProviders()
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

    Boolean hasValue(String valueName) {
        for (source in sources.reverse()) {
            var value = source.getValue(valueName)
            if (value) {
                return true
            }
        }
        false
    }

    String getValue(String valueName) {
        for (source in sources.reverse()) {
            var value = source.getValue(valueName)
            if (value) {
                return value
            }
        }
        throw new ApiScriptException("""Value dependency check failed.
Could not find value '${valueName}' in the following requests: ${sources.reverse()}""")
    }

    String interpolate(String text) {
        Utilities.replaceValueReferences(text, { Object[] m ->
            String valueName = m[1] as String
            getValue(valueName)
        })
    }
}

@TypeChecked
abstract class Provider {
    abstract String provideValueFrom(Response response)
}

@TypeChecked
class InBody extends Provider {
    String provideValueFrom(Response response) {
        response.body
    }
}

@TypeChecked
class InHeader extends Provider {
    String headerName
    InHeader(String headerName) {
        this.headerName = headerName
    }

    String provideValueFrom(Response response) {
        response.headers[headerName]
    }
}

@TypeChecked
class InJson extends Provider {

    static class PathElement {
        boolean isProperty() { false }
        boolean isArrayIndex() { false }
    }

    static class Property extends PathElement {
        private final String name
        Property(String name) { this.name = name }
        boolean isProperty() { true }
        @Override String toString() { name }
    }

    static class ArrayIndex extends PathElement {
        private final int index
        ArrayIndex(int index) { this.index = index }
        boolean isArrayIndex() { true }
        @Override String toString() { index.toString() }
    }

    final List<PathElement> jsonPath

    InJson(String jsonPath) {
        this.jsonPath = parseJsonPath(jsonPath)
    }

    String provideValueFrom(Response response) {
        if (response.isJson()) {
            try {
                var json = response.toJson()
                extractJsonValue(jsonPath, json)
            } catch (ApiScriptException ex) {
                throw new ApiScriptException("could not find JSON value at path '${jsonPath}'.  ${ex.getMessage()}", ex)
            }
        } else {
            throw new ApiScriptException("could not find JSON value at path '${jsonPath}' in non-JSON body: '${response.toString()}'")
        }
    }

    private String extractJsonValue(List<PathElement> path, Object json) {
        if (path.isEmpty()) {
            if (json instanceof String || json instanceof Float || json instanceof Boolean) {
                return json.toString()
            } else {
                throw new ApiScriptException("found non-scalar at path '${json}'")
            }
        } else {
            var head = path.head()
            if (json instanceof Map) {
                String property = head.toString()
                var obj = json as Map<String, Object>
                if (property in obj) {
                    extractJsonValue(path.tail(), obj[property]) 
                } else {
                    throw new ApiScriptException("key '${property}' not found in json: '${json}'")
                }
            } else if (json instanceof List) {
                List list = json as List
                if (head.isArrayIndex()) {
                    int index = (head as ArrayIndex).index
                    if (index < list.size()) {
                        return extractJsonValue(path.tail(), json[index])
                    } else {
                        throw new ApiScriptException("array ${json} only has ${list.size()} elements in it but element at index ${index} was requested.")
                    }
                } else {
                    throw new ApiScriptException("${head} is not a valid array index for '${json}'.")
                }
            } else {
                throw new ApiScriptException("key '${head}' not found in non-object: '${json}'")
            }
        }
    }

    /**
     * Given a String like "name", returns ["name"].
     * Given a String like "name[3]", returns ["name", 3].
     * Given a String like "[3]", returns [3].
     */
    static private List<PathElement> tokenize(String s) {
        final Pattern pattern = Pattern.compile(/(\w+)?\[(\d+)\]/)
        final Matcher m = s =~ pattern
        if (m.matches()) {
            if (m.group(1)) {
                [
                    new Property(m.group(1)),
                    new ArrayIndex(m.group(2).toInteger())
                ]
            } else {
                [
                    new ArrayIndex(m.group(2).toInteger())
                ]
            }
        } else {
            [
                new Property(s)
            ]
        }
    }

    static List<PathElement> parseJsonPath(String path) {
        path.split(/\./).collectMany{tokenize(it)}
    }
}

@TypeChecked
class Response {
    private final String body
    private final int statusCode
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
    private String contentType

    Response(int statusCode,
             Map<String, String> headers,
             String body) {
        this.statusCode = statusCode
        this.headers = headers
        this.body = body
        this.contentType = this.headers.'content-type'
    }

    boolean succeeded() {
        statusCode in (200 .. 302)
    }

    Object toJson() {
        if (body) {
            return new JsonSlurper().parseText(body)
        }
        return null
    }

    boolean isJson() {
        return contentType.startsWith("application/json")
    }

    String formattedBody() {
        Utilities.formatBodyText(body, headers["content-type"])
    }

    @Override
    String toString() {
        return """Status Code: ${statusCode}
Headers: ${Utilities.headersToString(headers)}
Body: ${body}"""
    }
}

@TypeChecked
trait HasEnvironment {
    static String env(String envVarName, String defaultValue = null) {
        Utilities.getEnvVar(envVarName, defaultValue)
    }
}

@TypeChecked
class HeaderDSL implements HasEnvironment {
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
class ParamDSL implements HasEnvironment {
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
class Utilities implements HasStyle {
    final static Pattern VALUE_NAME_REGEX = ~/\{\{([^}]*)\}\}/

    static headersToString(Map<String, String> headers) {
        headers.collect {"${it.key}: ${it.value}"}.join("\n")
    }

    static String paramsToString(List<Tuple2<String,String>> params, boolean encodeParams = true) {
        var result = params.collect {
            var encoded = encodeParams ? java.net.URLEncoder.encode(it.V2, "UTF-8") : it.V2
            "${it.V1}=${encoded}"
        }.join("&")
        result ? "?" + result : ""
    }

    static Set<String> findValueReferences(String text) {
        text.findAll(VALUE_NAME_REGEX).toSet()
    }

    static String replaceValueReferences(String text, Closure<String> c) {
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

    static <T> T timed(String operation, Closure<T> c) {
        def startTime = new Date().getTime()
        var result = c.call()
        def stopTime = new Date().getTime()
        inColor(BLUE) {
            println("${operation}: ${stopTime - startTime} milliseconds")
        }
        result
    }

    static formatBodyText(String text, String contentType = null) {
        if (contentType) {
            if (contentType.toLowerCase().startsWith("application/json")) {
                // You have a strange sense of pretty, JsonOutput.
                return JsonOutput.prettyPrint(text)
                    .split("\n")
                    .findAll { it.trim().length() > 0 }
                    .join("\n")
            }
        }
        return leftJustify(text)
    }

    static String leftJustify(String text) {
        var lines = text.split("\n")
        var shift = lines.collect { line ->
            line.toList().findIndexOf { it != ' ' }
        }.findAll { it > 0 }.min()
        if (shift) {
            lines.collect { it.length() > shift ? it[shift .. -1] : it }.join("\n")
        } else {
            text
        }
    }

    static void fatalError(Exception ex) {
        fatalError(ex.getMessage())
    }

    static void fatalError(String error) {
        inColor RED, {
            println(error)
        }
        println(ansi().a(Attribute.RESET))
        AnsiConsole.systemUninstall()
        System.exit(1)
    }
}

class TokenSource {
    final RequestDSL request
    String tokenName

    TokenSource(RequestDSL request) {
        this.request = request
    }

    TokenSource propertyMissing(String name) {
        tokenName = name
        return this
    }
}

@TypeChecked
public class ApiScriptException extends Exception {
    ApiScriptException(String what) {
        super(what)
    }

    ApiScriptException(String what, Exception cause) {
        super(what, cause)
    }
}

@TypeChecked
public class SyntaxError extends ApiScriptException {
    SyntaxError(String what) {
        super(what)
    }
}

@TypeChecked
public class EvaluationError extends ApiScriptException {
    EvaluationError(String what) {
        super(what)
    }
}
