package org.blendee.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.blendee.internal.U;

/**
 * {@link MessageFormat} を使用してコードを組み立てるデフォルトのフォーマッタです。
 * @author 千葉 哲嗣
 */
@SuppressWarnings("javadoc")
public class Formatter {

	private static final Pattern pattern = Pattern.compile(
		"\\[\\[([^\\]]+)\\]\\]",
		Pattern.MULTILINE + Pattern.DOTALL);

	public static String format(String template, Map<String, String> arguments) {
		StringBuilder buffer = new StringBuilder();

		Matcher matcher = pattern.matcher(template);

		int start = 0;
		while (matcher.find()) {
			buffer.append(template.substring(start, matcher.start()));
			buffer.append(arguments.get(matcher.group(1)));
			start = matcher.end();
		}

		buffer.append(template.substring(start));

		return buffer.toString();
	}

	public static String convertToTemplate(String source) {
		source = source.replaceAll("/\\*--\\*/.+?/\\*--\\*/", "");
		return source.replaceAll("/\\*\\+\\+(.+?)\\+\\+\\*/", "$1");
	}

	public static String readTemplate(Class<?> target, String charset) {
		try (InputStream input = target.getResourceAsStream(target.getSimpleName() + ".java")) {
			return new String(U.readBytes(input), charset);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
