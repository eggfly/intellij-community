// "Create annotation 'foo'" "true"

[foo(fooBar = 1)] fun test() {

}

annotation class foo(val fooBar: Int)
