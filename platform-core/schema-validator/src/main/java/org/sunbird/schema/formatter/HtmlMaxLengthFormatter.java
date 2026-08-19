package org.sunbird.schema.formatter;

import org.jsoup.Jsoup;
import org.leadpony.justify.api.InstanceType;
import org.leadpony.justify.spi.FormatAttribute;

import javax.json.JsonString;
import javax.json.JsonValue;

/**
 * Format attribute that validates a string's visible text length - i.e. after
 * stripping HTML markup and decoding entities - against a fixed limit, instead
 * of counting the raw markup characters like the standard "maxLength" keyword does.
 * Any schema property can opt in by declaring "format": "html-maxlength-1000".
 */
public class HtmlMaxLengthFormatter implements FormatAttribute {

    private static final int LIMIT = 1000;

    @Override
    public String name() {
        return "html-maxlength-" + LIMIT;
    }

    @Override
    public InstanceType valueType() {
        return InstanceType.STRING;
    }

    @Override
    public boolean test(JsonValue value) {
        String str = ((JsonString) value).getString();
        String text = Jsoup.parse(str).text();
        return text.codePointCount(0, text.length()) <= LIMIT;
    }
}
