package cloud.fhwang.chaewon_t.render;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** HWPX XML을 네임스페이스 인식 DOM으로 읽고 쓰는 도우미. 접두어(hp: 등)는 파일마다 다를 수 있어 localName으로 찾는다 */
final class XmlSupport {

	private XmlSupport() {
	}

	static Document parse(byte[] xml) throws IOException {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			// 외부 엔티티(XXE) 차단
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setXIncludeAware(false);
			f.setExpandEntityReferences(false);
			return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("XML을 읽지 못했습니다.", e);
		}
	}

	static byte[] serialize(Document dom) throws IOException {
		try {
			TransformerFactory tf = TransformerFactory.newInstance();
			tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
			Transformer t = tf.newTransformer();
			t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			// 원본 선언의 standalone="yes"를 유지한다 (지정하지 않으면 JDK가 빼 버린다)
			if (dom.getXmlStandalone()) {
				t.setOutputProperty(OutputKeys.STANDALONE, "yes");
			}
			// 들여쓰기를 넣지 않는다: 공백도 문서 내용이 될 수 있다.
			// 네임스페이스 선언 순서는 바뀔 수 있다 (XML 의미는 같음)
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			t.transform(new DOMSource(dom), new StreamResult(out));
			return out.toByteArray();
		} catch (TransformerException e) {
			throw new IOException("XML을 쓰지 못했습니다.", e);
		}
	}

	/** 직계 자식 중 localName이 같은 요소 */
	static List<Element> children(Element parent, String localName) {
		List<Element> result = new ArrayList<>();
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && localName.equals(e.getLocalName())) {
				result.add(e);
			}
		}
		return result;
	}

	/** 모든 하위 요소 중 localName이 같은 요소 (복사본 목록이라 순회 중 수정해도 된다) */
	static List<Element> descendants(Element root, String localName) {
		NodeList list = root.getElementsByTagNameNS("*", localName);
		List<Element> result = new ArrayList<>(list.getLength());
		for (int i = 0; i < list.getLength(); i++) {
			result.add((Element) list.item(i));
		}
		return result;
	}

	static void removeAll(List<Element> elements) {
		for (Element e : elements) {
			e.getParentNode().removeChild(e);
		}
	}

	/** parent와 같은 네임스페이스·접두어로 새 요소를 만든다 (예: hp:p 옆에 hp:t) */
	static Element createSibling(Element like, String localName) {
		String prefix = like.getPrefix();
		String qualified = prefix == null ? localName : prefix + ":" + localName;
		return like.getOwnerDocument().createElementNS(like.getNamespaceURI(), qualified);
	}
}
