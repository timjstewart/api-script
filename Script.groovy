import static Dsl.*

var getToken =
    post "https://example.com", {
    headers "accept": "application/json",
        "foo": "bar"
    param "algo", "SHA-256"
    json '''
    {
        "name": "Tim",
        "hobbies": [ "guitar", "programming" ]
    }
    '''
    store "token" jpath ".*"
}

var getUser = get "https://example.com/user/{user}", {
    headers "accept": "application/json",
        "Bearer": (from "token")
    param "algo", "SHA-1"
    json '''
    {
        "name": "Tim",
        "hobbies": [ "guitar", "programming" ]
    }
    '''
}



runIt getToken, getUser

println("DONE")
