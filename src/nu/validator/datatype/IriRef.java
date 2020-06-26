/*
 * Copyright (c) 2006 Henri Sivonen
 * Copyright (c) 2007-2018 Mozilla Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package nu.validator.datatype;

import java.io.IOException;
import java.io.InputStream;

import org.relaxng.datatype.DatatypeException;
import nu.validator.io.DataUri;
import nu.validator.io.DataUriException;

public class IriRef extends AbstractDatatype {

    private static final int ELIDE_LIMIT = 50;

    /**
     * The singleton instance.
     */
    public static final IriRef THE_INSTANCE = new IriRef();

    protected IriRef() {
        super();
    }

    private final static boolean WARN = System.getProperty("nu.validator.datatype.warn", "").equals("true");

    private final CharSequencePair splitScheme(CharSequence iri) {
        StringBuilder sb = new StringBuilder();
        Boolean atSchemeBeginning = true;
        for (int i = 0; i < iri.length(); i++) {
            char c = toAsciiLowerCase(iri.charAt(i));
            if (atSchemeBeginning) {
                // Skip past any leading characters that the HTML5 spec defines
                // as space characters: space, tab, LF, FF, CR
                if (' ' == c || '\t' == c || '\n' == c || '\f' == c
                        || '\r' == c) {
                    continue;
                }
                if ('a' <= c && 'z' >= c) {
                    atSchemeBeginning = false;
                    sb.append(c);
                } else {
                    return null;
                }
            } else {
                if (('a' <= c && 'z' >= c) || ('0' <= c && '9' >= c)
                        || c == '+' || c == '.') {
                    sb.append(c);
                    continue;
                } else if (c == ':') {
                    return new CharSequencePair(sb, iri.subSequence(i + 1,
                            iri.length()));
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public void checkValid(CharSequence literal) throws DatatypeException {
    }

    private final boolean isHttpOrHttps(CharSequence scheme) {
        return "http".contentEquals(scheme) || "https".contentEquals(scheme);
    }

    private final boolean isHttpAlias(CharSequence scheme) {
        return "feed".contentEquals(scheme) || "webcal".contentEquals(scheme);
    }

    private final boolean isWellKnown(CharSequence scheme) {
        return "http".contentEquals(scheme) || "https".contentEquals(scheme)
                || "ftp".contentEquals(scheme)
                || "mailto".contentEquals(scheme)
                || "file".contentEquals(scheme);
    }

    protected boolean isAbsolute() {
        return false;
    }

    protected boolean reportValue() {
        return false;
    }

    protected static final String trimHtmlSpaces(String str) {
        return trimHtmlLeadingSpaces(trimHtmlTrailingSpaces(str));
    }

    protected static final String trimHtmlLeadingSpaces(String str) {
        if (str == null) {
            return null;
        }
        for (int i = str.length(); i > 0; --i) {
            char c = str.charAt(str.length() - i);
            if (!(' ' == c || '\t' == c || '\n' == c || '\f' == c || '\r' == c)) {
                return str.substring(str.length() - i, str.length());
            }
        }
        return "";
    }

    protected static final String trimHtmlTrailingSpaces(String str) {
        if (str == null) {
            return null;
        }
        for (int i = str.length() - 1; i >= 0; --i) {
            char c = str.charAt(i);
            if (!(' ' == c || '\t' == c || '\n' == c || '\f' == c || '\r' == c)) {
                return str.substring(0, i + 1);
            }
        }
        return "";
    }

    protected boolean mustBeHttpOrHttps() {
        return false;
    }

    @Override
    public String getName() {
        return "URL";
    }

    private class CharSequencePair {
        private final CharSequence head;
        private final CharSequence tail;

        /**
         * @param head
         * @param tail
         */
        public CharSequencePair(final CharSequence head, final CharSequence tail) {
            this.head = head;
            this.tail = tail;
        }

        /**
         * Returns the head.
         * 
         * @return the head
         */
        public CharSequence getHead() {
            return head;
        }

        /**
         * Returns the tail.
         * 
         * @return the tail
         */
        public CharSequence getTail() {
            return tail;
        }
    }
}
