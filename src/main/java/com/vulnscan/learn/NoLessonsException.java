package com.vulnscan.learn;

import com.vulnscan.UserFacingException;

/** Status no_lessons: the with-lessons arm is never run with an empty guidance block. */
public class NoLessonsException extends UserFacingException {
    public NoLessonsException(String message) {
        super("no_lessons: " + message);
    }
}
