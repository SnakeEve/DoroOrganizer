package com.doro.organizer.gates.dependencies

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import java.io.Serializable

/**
 * The external modules of one resolved configuration as `group:artifact`: [resolved] were selected, [unresolved]
 * were requested but failed to resolve. A selector that is not a module keeps its display name.
 */
data class ModuleGraph(val resolved: List<String>, val unresolved: List<String>) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        /** Walks the graph below [root]; constraint-only edges add nothing. */
        fun of(root: ResolvedComponentResult): ModuleGraph {
            val seen = HashSet<ResolvedComponentResult>()
            val unresolved = sortedSetOf<String>()
            val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                val component = pending.removeFirst()
                if (!seen.add(component)) continue
                component.dependencies.filterNot { it.isConstraint }.forEach { dependency ->
                    when (dependency) {
                        is ResolvedDependencyResult -> pending.addLast(dependency.selected)
                        is UnresolvedDependencyResult -> unresolved += dependency.requested.let { requested ->
                            (requested as? ModuleComponentSelector)?.let { "${it.group}:${it.module}" } ?: requested.displayName
                        }
                    }
                }
            }
            val resolved = seen.mapNotNull { it.id as? ModuleComponentIdentifier }.map { "${it.group}:${it.module}" }.distinct().sorted()
            return ModuleGraph(resolved, unresolved.toList())
        }
    }
}
