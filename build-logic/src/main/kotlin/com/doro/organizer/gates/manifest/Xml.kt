package com.doro.organizer.gates.manifest

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

/** Parses [text] namespace-aware and returns its root element; DOCTYPEs are rejected. */
internal fun parseXml(text: String): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
}

/** The `android:` attribute [name], or null when it is absent. */
internal fun Element.android(name: String): String? =
    if (hasAttributeNS(ANDROID_NAMESPACE, name)) getAttributeNS(ANDROID_NAMESPACE, name) else null

/** Direct child elements, optionally only those named [tag]. */
internal fun Element.children(tag: String? = null): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).map(nodes::item).filterIsInstance<Element>().filter { tag == null || it.tagName == tag }
}

/** This element and every element below it, in document order. */
internal fun Element.selfAndDescendants(): Sequence<Element> =
    sequenceOf(this) + children().asSequence().flatMap { it.selfAndDescendants() }

/** Non-namespace-declaration attributes as `name to value`. */
internal fun Element.plainAttributes(): Map<String, String> {
    val attributes = attributes
    return (0 until attributes.length).map(attributes::item)
        .filter { it.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI }
        .associate { it.nodeName to it.nodeValue }
}

internal fun Element.describe(): String = android("name")?.let { "<$tagName $it>" } ?: "<$tagName>"
