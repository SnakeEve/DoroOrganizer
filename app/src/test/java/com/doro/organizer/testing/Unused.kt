package com.doro.organizer.testing

import java.lang.reflect.Proxy

/**
 * A collaborator the code under test must never reach, for constructors that need one: every call fails the test.
 * It answers nothing, so it is no mock (DEC-12); a fake that needs a few real members delegates the rest to it.
 */
inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
    if (method.name == "toString") "unused ${T::class.java.simpleName}" else throw AssertionError("unexpected call: ${method.name}")
} as T
