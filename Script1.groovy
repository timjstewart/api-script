import static ApiScriptDSL.*

var req1 = POST "http://httpbin.org/anything", {
    header 'Accept' 'application/json'
    header 'Content-Type' 'application/json'
    header 'SECRET' env("SECRET_TOKEN", "SEcR3t")
    header 'User-Agent' links
    param 'order' slug
    param 'limit' 3
    param 'user' env("USER", "anonymous")
    body '''
    {
      "title": "My Blog",
      "slug": "my-blog",
      "text": "This is my blog."
    }
    '''
    provides "host" from header "server";
    provides "host" from header "server";
    provides "verb" from json "method";
    provides "trace-id" from json "headers.X-Amzn-Trace-Id";
}

var req2 = GET "https://httpbin.org/anything", {
    header 'Accept' "application/json"
    header 'token' "{{verb}} {{host}} {{trace-id}}"
    param "cache-buster" 12345
}

var req3 = GET "https://httpbin.org/status/400", {
}
send req1, req2, req3

println("DONE")
