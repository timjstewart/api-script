import static ApiScriptDSL.*

var req1 = post {
    url "http://httpbin.org/anything"
    header "Accept" "application/json"
    header "Content-Type" "application/json"
    header "User-Agent" "links"
    param "order" "slug"
    param "limit" 3
    body '''
    {
      "title": "My Blog",
      "slug": "my-blog",
      "text": "This is my blog."
    }
    '''
}

var req2 = get {
    url "https://www.example.com"
    header "Accept" "application/json"
    param "cache-buster" "12345"
    body '''
    {
      "title": "My Blog",
      "slug": "my-blog",
      "text": "This is my blog."
    }
    '''
}

send req1
println("DONE")
