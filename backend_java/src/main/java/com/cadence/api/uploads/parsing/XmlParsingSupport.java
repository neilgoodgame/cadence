package com.cadence.api.uploads.parsing;

import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Shared DOM helpers for the GPX/TCX parsers: namespace-agnostic matching by local name only, since producers vary widely in which prefixes/namespaces they use. */
final class XmlParsingSupport {

	static Document parse(java.io.InputStream inputStream) throws ParserConfigurationException, SAXException, java.io.IOException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		// XXE hardening: this is untrusted user-uploaded content.
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		DocumentBuilder builder = factory.newDocumentBuilder();
		return builder.parse(inputStream);
	}

	/** Direct child elements matching a local name, ignoring namespace/prefix. */
	static List<Element> children(Element parent, String localName) {
		List<Element> result = new ArrayList<>();
		NodeList children = parent.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node node = children.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE && localName.equalsIgnoreCase(node.getLocalName())) {
				result.add((Element) node);
			}
		}
		return result;
	}

	static Element firstChild(Element parent, String localName) {
		List<Element> matches = children(parent, localName);
		return matches.isEmpty() ? null : matches.get(0);
	}

	/** Any descendant matching a local name, ignoring namespace/prefix - for digging into vendor extension blocks. */
	static Element firstDescendant(Element parent, String localName) {
		NodeList all = parent.getElementsByTagNameNS("*", localName);
		return all.getLength() > 0 ? (Element) all.item(0) : null;
	}

	static Double textAsDouble(Element element) {
		if (element == null) {
			return null;
		}
		String text = element.getTextContent();
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return Double.parseDouble(text.trim());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	static Integer textAsInt(Element element) {
		Double d = textAsDouble(element);
		return d != null ? d.intValue() : null;
	}

	private XmlParsingSupport() {
	}
}
