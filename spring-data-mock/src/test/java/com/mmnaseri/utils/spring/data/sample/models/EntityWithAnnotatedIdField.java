package com.mmnaseri.utils.spring.data.sample.models;

import org.springframework.data.annotation.Id;

/**
 * @author Milad Naseri (mmnaseri@programmer.net)
 * @since 1.0 (4/12/16, 6:52 PM)
 */
public class EntityWithAnnotatedIdField {

    @Id
    private String id;

}
