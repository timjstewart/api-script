import static ApiScriptDSL.*

var req1 = POST "http://httpbin.org/anything", {
    header Accept 'application/json'
    header 'Content-Type' 'application/json'
    header 'User-Agent' links
    param order slug
    param limit 3
    body '''
    {
      "title": "My Blog",
      "slug": "my-blog",
      "text": "This is my blog."
    }
    '''
    provides "host" from header "server"
}

var req2 = GET "https://httpbin.org/anything", {
    header Accept "application/json"
    header token "{{host}}"
    param "cache-buster" 12345
    requires "host"
}

send req1, req2
println("DONE")
