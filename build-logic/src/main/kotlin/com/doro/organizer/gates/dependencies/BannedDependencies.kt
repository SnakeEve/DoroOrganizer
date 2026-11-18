package com.doro.organizer.gates.dependencies

import com.doro.organizer.gates.configEntries

/**
 * `config/banned-dependencies.txt` (testing.md § 9 `dependencies`). A module `g:a` matches an entry `L` when
 * `g == L`, when `g` starts with `L.`, or when `g:a == L`.
 */
internal class BannedDependencies(private val entries: List<String>) {
    /** The first entry that bans [module] (`group:artifact`), or null. */
    fun banningEntry(module: String): String? {
        val group = module.substringBefore(':')
        return entries.firstOrNull { group == it || group.startsWith("$it.") || module == it }
    }

    /**
     * [graphs] maps a configuration name to its module graph. A banned module fails whether it resolved or not, and
     * any unresolved module fails too: the gate never passes on a graph it could not see completely.
     */
    fun violations(graphs: Map<String, ModuleGraph>): List<String> =
        graphs.toSortedMap().flatMap { (configuration, graph) ->
            (graph.resolved + graph.unresolved).distinct().sorted().mapNotNull { module ->
                banningEntry(module)?.let { "$module in $configuration (banned by '$it')" }
            } + graph.unresolved.map { "$it in $configuration could not be resolved" }
        }

    companion object {
        fun parse(text: String) = BannedDependencies(configEntries(text))
    }
}
