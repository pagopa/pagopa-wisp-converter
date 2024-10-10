package it.gov.pagopa.wispconverter.util;

import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import jakarta.xml.bind.*;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class JaxbElementUtil {

    public SOAPMessage getMessage(String payload) {
        return getMessage(payload.getBytes(StandardCharsets.UTF_8));
    }
    public SOAPMessage getMessage(byte[] payload) {
        try{
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(payload);
            SOAPMessage message = MessageFactory.newInstance().createMessage(null, byteArrayInputStream);
            byteArrayInputStream.close();
            return message;
        } catch (IOException | SOAPException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR, e.getMessage());
        }
    }
    public <T> T getHeader(SOAPMessage message,Class<T> headerclass){
        try {
            return convertToBean(message.getSOAPHeader().extractAllHeaderElements().next(),headerclass);
        } catch (SOAPException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_INVALID_HEADER, e.getMessage());
        }
    }

    public <T> T getBody(SOAPMessage message,Class<T> bodyClass) {
        try {
            return convertToBean(message.getSOAPBody().extractContentAsDocument(),bodyClass);
        } catch (SOAPException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_INVALID_BODY, e.getMessage());
        }
    }

    public <T> T convertToBean(Node element, Class<T> targetType) {
        try {
            JAXBContext context = JAXBContext.newInstance(targetType);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            JAXBElement<T> jaxbElement = unmarshaller.unmarshal(element, targetType);
            return jaxbElement.getValue();
        } catch (JAXBException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR, e.getMessage());
        }
    }
    public <T> T convertToBean(byte[] xml, Class<T> targetType) {
        try {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(xml);
            JAXBContext context = JAXBContext.newInstance(targetType);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            JAXBElement<T> jaxbElement = unmarshaller.unmarshal(new StreamSource(byteArrayInputStream),targetType);
            byteArrayInputStream.close();
            return (T)jaxbElement.getValue();
        } catch (JAXBException | IOException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR, e.getMessage());
        }
    }
    public <T> T convertToBean(String xml, Class<T> targetType) {
        return convertToBean(xml.getBytes(StandardCharsets.UTF_8),targetType);
    }

    public SOAPMessage newMessage(){
        try {
            return MessageFactory.newInstance().createMessage();
        } catch (SOAPException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR, e.getMessage());
        }
    }

    public void addBody(SOAPMessage message,Object bodyObject,Class bodyClass){
        JAXBContext context = null;
        try {
            context = JAXBContext.newInstance(bodyClass);
            Marshaller marshaller = context.createMarshaller();
            marshaller.marshal(bodyObject, message.getSOAPBody());
        } catch (JAXBException | SOAPException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR, e.getMessage());
        }
    }

    public void addHeader(SOAPMessage message,Object headerObject,Class headerClass){
        JAXBContext context = null;
        try {
            context = JAXBContext.newInstance(headerClass);
            Marshaller marshaller = context.createMarshaller();
            marshaller.marshal(headerObject, message.getSOAPHeader());
        } catch (JAXBException | SOAPException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR, e.getMessage());
        }
    }

    public String toString(SOAPMessage message){
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            message.writeTo(byteArrayOutputStream);
            byteArrayOutputStream.close();
        } catch (SOAPException | IOException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR, e.getMessage());
        }
        String ss = new String(byteArrayOutputStream.toByteArray(),StandardCharsets.UTF_8);
        return ss;
    }

    public String objectToString(Object element){
        try {
            StringWriter stringWriter = new StringWriter();
            Class c;
            if(element instanceof JAXBElement element1){
                c = element1.getValue().getClass();
            }else{
                c = element.getClass();
            }
            JAXBContext jaxbContext = JAXBContext.newInstance(c);
            Marshaller marshaller = jaxbContext.createMarshaller();
//            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE); // this removes XML declaration
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            marshaller.setProperty("org.glassfish.jaxb.namespacePrefixMapper", new RTNamespaceMapper());
            marshaller.setProperty(Marshaller.JAXB_SCHEMA_LOCATION, "http://www.digitpa.gov.it/schemas/2011/Pagamenti/ PagInf_RPT_RT_6_0_1.xsd");
            marshaller.marshal(element,stringWriter);
            return stringWriter.toString();
        } catch (JAXBException e) {
            throw new AppException(e, AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR, e.getMessage());
        }
    }

}
