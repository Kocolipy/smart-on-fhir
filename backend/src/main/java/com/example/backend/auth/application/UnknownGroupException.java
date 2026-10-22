package com.example.backend.auth.application;

/**
 * No Group has the id a change of a Group's Permissions addressed — it never existed, or a connector
 * has since deleted it. The web adapter renders it as a {@code 404}: the caller is the Superuser,
 * so naming what is missing reveals nothing the Groups listing would not.
 */
public class UnknownGroupException extends RuntimeException {

    public UnknownGroupException() {
        super("no Group has this id");
    }
}
