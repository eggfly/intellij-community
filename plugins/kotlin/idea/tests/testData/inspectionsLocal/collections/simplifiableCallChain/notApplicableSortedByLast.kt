// PROBLEM: none
// LANGUAGE_VERSION: 1.3
// WITH_RUNTIME
fun main() {
    listOf(1, null, 2).<caret>sortedBy { it }.last()
}