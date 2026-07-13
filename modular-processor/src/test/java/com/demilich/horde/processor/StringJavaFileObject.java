package com.demilich.horde.processor;

import java.net.URI;
import javax.tools.SimpleJavaFileObject;

/** An in-memory Java source file, for feeding fixture source text directly to the compiler. */
class StringJavaFileObject extends SimpleJavaFileObject {

    private final String content;

    StringJavaFileObject(String qualifiedClassName, String content) {
        super(URI.create("string:///" + qualifiedClassName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
        this.content = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return content;
    }
}
