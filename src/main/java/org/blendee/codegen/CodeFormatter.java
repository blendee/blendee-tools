package org.blendee.codegen;

import java.util.Map;

import org.blendee.assist.Row;
import org.blendee.assist.SelectStatement;

/**
 * {@link TableFacadeGenerator} で生成されるクラスの出力前に割り込み、コードを組み立てるインターフェイスです。
 * @author 千葉 哲嗣
 */
public interface CodeFormatter {

	/**
	 * {@link TableFacadeTemplate} をテンプレートとしたコードを組み立てます。
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String format(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}

	/**
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String formatColumnNamesPart(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}

	/**
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String formatPrimaryKeyPart(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}

	/**
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String formatForeignKeysPart(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}

	/**
	 * {@link Row} の setter getter 生成部分のコードを組み立てます。
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String formatRowPropertyAccessorPart(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}

	/**
	 * {@link Row} のリレーション生成部分のコードを組み立てます。
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String formatRowRelationshipPart(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}

	/**
	 * {@link SelectStatement} の項目生成部分のコードを組み立てます。<br>
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String formatRelationshipColumnPart1(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}

	/**
	 * {@link SelectStatement} の項目生成部分のコードを組み立てます。<br>
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String formatRelationshipColumnPart2(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}

	/**
	 * {@link SelectStatement} のリレーション生成部分のコードを組み立てます。<br>
	 * @param template テンプレート
	 * @param arguments 引数
	 * @return 生成後のコード
	 */
	default String formatTableRelationshipPart(String template, Map<String, String> arguments) {
		return Formatter.format(template, arguments);
	}
}
