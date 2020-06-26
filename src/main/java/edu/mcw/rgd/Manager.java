package edu.mcw.rgd;

import edu.mcw.rgd.process.Utils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

/**
 * @author mtutaj
 * @since Dec 12, 2017
 */
public class Manager {

    public static void main(String[] args) throws Exception {

        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));

        Loader loader = (Loader) (bf.getBean("loader"));
        try {
            loader.run();
        } catch (Exception e) {
            Utils.printStackTrace(e, loader.log);
            throw e;
        }
    }
}

