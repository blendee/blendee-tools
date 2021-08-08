package org.blendee.codegen;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.SourceVersion;

import org.blendee.assist.Many;
import org.blendee.assist.TableFacadePackageRule;
import org.blendee.assist.annotation.ForeignKey;
import org.blendee.assist.annotation.PrimaryKey;
import org.blendee.internal.U;
import org.blendee.jdbc.BlendeeManager;
import org.blendee.jdbc.ColumnMetadata;
import org.blendee.jdbc.CrossReference;
import org.blendee.jdbc.Metadata;
import org.blendee.jdbc.PrimaryKeyMetadata;
import org.blendee.jdbc.TableMetadata;
import org.blendee.jdbc.TablePath;
import org.blendee.sql.Column;
import org.blendee.sql.Relationship;
import org.blendee.sql.RelationshipFactory;
import org.blendee.util.DatabaseInfo;
import org.blendee.util.DatabaseInfoWriter;

/**
 * データベースの構成を読み取り、各テーブルクラスの Java ソースを生成するジェネレータクラスです。
 * @author 千葉 哲嗣
 */
public class TableFacadeGenerator {

	private static final CodeFormatter defaultCodeFormatter = new CodeFormatter() {
	};

	private static final String template;

	private static final String columnNamesPartTemplate;

	private static final String primaryKeyPartTemplate;

	private static final String foreignKeysPartTemplate;

	private static final String rowPropertyAccessorPartTemplate;

	private static final String rowRelationshipPartTemplate;

	private static final String relationshipColumnPart1Template;

	private static final String relationshipColumnPart2Template;

	private static final String tableRelationshipPartTemplate;

	private static final Map<Class<?>, Class<?>> primitiveToWrapperMap = new HashMap<>();

	private final Metadata metadata;

	private final String rootPackageName;

	private final Class<?> tableFacadeSuperclass;

	private final Class<?> rowSuperclass;

	private final CodeFormatter codeFormatter;

	private final boolean useNumberClass;

	private final boolean useNullGuard;

	static {
		primitiveToWrapperMap.put(boolean.class, Boolean.class);
		primitiveToWrapperMap.put(byte.class, Byte.class);
		primitiveToWrapperMap.put(char.class, Character.class);
		primitiveToWrapperMap.put(short.class, Short.class);
		primitiveToWrapperMap.put(int.class, Integer.class);
		primitiveToWrapperMap.put(long.class, Long.class);
		primitiveToWrapperMap.put(float.class, Float.class);
		primitiveToWrapperMap.put(double.class, Double.class);
		primitiveToWrapperMap.put(void.class, Void.class);
	}

	static {
		String source = Formatter.readTemplate(TableFacadeTemplate.class, "UTF-8");
		{
			String[] result = pickupFromSource(source, "ColumnNamesPart");
			columnNamesPartTemplate = Formatter.convertToTemplate(result[0]);
			source = result[1];
		}

		{
			String[] result = pickupFromSource(source, "PrimaryKeyPart");
			primaryKeyPartTemplate = Formatter.convertToTemplate(result[0]);
			source = result[1];
		}

		{
			String[] result = pickupFromSource(source, "ForeignKeysPart");
			foreignKeysPartTemplate = Formatter.convertToTemplate(result[0]);
			source = result[1];
		}

		{
			String[] result = pickupFromSource(source, "RowPropertyAccessorPart");
			rowPropertyAccessorPartTemplate = Formatter.convertToTemplate(result[0]);
			source = result[1];
		}

		{
			String[] result = pickupFromSource(source, "RowRelationshipPart");
			rowRelationshipPartTemplate = Formatter.convertToTemplate(result[0]);
			source = result[1];
		}

		{
			String[] result = pickupFromSource(source, "ColumnPart1");
			relationshipColumnPart1Template = Formatter.convertToTemplate(result[0]);
			source = result[1];
		}

		{
			String[] result = pickupFromSource(source, "ColumnPart2");
			relationshipColumnPart2Template = Formatter.convertToTemplate(result[0]);
			source = result[1];
		}

		{
			String[] result = pickupFromSource(source, "TableRelationshipPart");
			tableRelationshipPartTemplate = Formatter.convertToTemplate(result[0]);
			source = result[1];
		}

		template = Formatter.convertToTemplate(source);
	}

	/**
	 * インスタンスを生成します。
	 * @param metadata テーブルを読み込む対象となるデータベースの {@link Metadata}
	 * @param rootPackageName 各自動生成クラスが属するパッケージの親パッケージ
	 * @param tableFacadeSuperclass TableFacade クラスの親クラス
	 * @param rowSuperclass Row クラスの親クラス
	 * @param codeFormatter {@link CodeFormatter}
	 * @param useNumberClass Row クラスの数値型項目を {@link Number} で統一する
	 * @param useNullGuard Row クラスの項目に null ガードを適用する
	 */
	public TableFacadeGenerator(
		Metadata metadata,
		String rootPackageName,
		Class<?> tableFacadeSuperclass,
		Class<?> rowSuperclass,
		CodeFormatter codeFormatter,
		boolean useNumberClass,
		boolean useNullGuard) {
		this.metadata = Objects.requireNonNull(metadata);
		this.rootPackageName = Objects.requireNonNull(rootPackageName);
		this.tableFacadeSuperclass = tableFacadeSuperclass != null ? tableFacadeSuperclass : Object.class;
		this.rowSuperclass = rowSuperclass != null ? rowSuperclass : Object.class;

		this.codeFormatter = codeFormatter == null ? defaultCodeFormatter : codeFormatter;

		this.useNumberClass = useNumberClass;
		this.useNullGuard = useNullGuard;
	}

	/**
	 * 自動生成可能なテーブル名かどうか判定します。
	 * @param name テーブル名
	 * @return 生成可能な場合 true
	 */
	public static boolean isGeneratableTableName(String name) {
		return SourceVersion.isName(name);
	}

	/**
	 * すべてのクラスファイルを生成します。
	 * @param schemaName 対象となるスキーマ
	 * @param home 生成された Java ソースを保存するためのルートとなる場所
	 * @param srcCharset 生成する Java ソースの文字コード
	 * @throws IOException ファイル書き込みに失敗した場合
	 */
	public void build(String schemaName, File home, Charset srcCharset) throws IOException {
		File rootPackageDir = getRootPackageDir(home);
		rootPackageDir.mkdirs();

		File packageDir = new File(rootPackageDir, TableFacadePackageRule.care(schemaName));
		packageDir.mkdir();

		TablePath[] tables = metadata.getTables(schemaName);
		RelationshipFactory factory = RelationshipFactory.getInstance();
		for (TablePath table : tables) {
			Relationship relation = factory.getInstance(table);

			String tableName = relation.getTablePath().getTableName();

			//使用できない名前の場合
			if (!isGeneratableTableName(tableName)) {
				//"使用できない名前: " + tableName
				BlendeeManager.getLogger().log(Level.WARNING, "invalid name: " + tableName);
				return;
			}

			write(
				new File(packageDir, createCompilationUnitName(tableName)),
				build(relation),
				srcCharset);
		}
	}

	/**
	 * このクラスが生成する Table のコンパイル単位名を返します。
	 * @param tableName 対象となるテーブル名
	 * @return コンパイル単位名
	 */
	public static String createCompilationUnitName(String tableName) {
		checkName(tableName);
		return tableName + ".java";
	}

	/**
	 * データベース全体の情報をファイルに保存します。
	 * @param home 生成された Java ソースを保存するためのルートとなる場所
	 * @throws IOException ファイル書き込みに失敗した場合
	 */
	public void writeDatabaseInfo(Path home) throws IOException {
		DatabaseInfoWriter info = new DatabaseInfoWriter(home, rootPackageName);

		info.mkdirs();

		Properties properties = new Properties();

		DatabaseInfo.setStoredIdentifier(properties, metadata.getStoredIdentifier());

		info.write(properties);
	}

	private File getRootPackageDir(File home) {
		return new File(home, String.join("/", rootPackageName.split("\\.")));
	}

	private static String createIndent(int n) {
		return String.join("", Collections.nCopies(n, "\t"));
	}

	/**
	 * Table クラスを一件作成します。
	 * @param relation 対象となるテーブルをあらわす {@link Relationship}
	 * @return 生成されたソース
	 */
	public String build(Relationship relation) {
		//relation はルートでなければなりません
		if (!relation.isRoot()) throw new IllegalArgumentException("\"relation\" must be root");

		TablePath target = relation.getTablePath();

		String schemaName = target.getSchemaName();

		String packageName = rootPackageName + "." + TableFacadePackageRule.care(schemaName);

		String tableName = target.getTableName();

		checkName(tableName);

		Set<String> importPart = new LinkedHashSet<>();

		String columnNamesPart, propertyAccessorPart, columnPart1, columnPart2;
		{
			List<String> columnNames = new LinkedList<>();
			List<String> properties = new LinkedList<>();
			List<String> list1 = new LinkedList<>();
			List<String> list2 = new LinkedList<>();

			for (Column column : relation.getColumns()) {
				Class<?> type = column.getType();

				String classNameString;

				if (type.isArray()) {
					Class<?> componentType = convertForNumber(type.getComponentType());
					classNameString = componentType.getName() + "[]";
				} else {
					classNameString = convertForNumber(convertPrimitiveClassToWrapperClass(column.getType())).getName();
				}

				String cast = "";

				if (!classNameString.equals(Object.class.getName())) {
					cast = "(" + classNameString + ") ";
				}

				ColumnMetadata columnMetadata = column.getColumnMetadata();

				boolean notNull = columnMetadata.isNotNull();

				String nullCheck = "", returnPrefix = "", returnSuffix = "", returnType = classNameString;
				boolean returnOptional = false;
				if (useNullGuard) {
					if (notNull || column.isPrimaryKey()) {
						nullCheck = Objects.class.getSimpleName() + ".requireNonNull(value);" + U.LINE_SEPARATOR + "\t\t\t";
					} else {
						String optional = Optional.class.getSimpleName();
						returnPrefix = optional + ".ofNullable(";
						returnSuffix = ")";
						returnType = optional + "<" + classNameString + ">";
						returnOptional = true;
					}
				}

				String columnName = safe(column.getName());

				Map<String, String> args = new HashMap<>();
				args.put("PACKAGE", packageName);
				args.put("TABLE", tableName);
				args.put("METHOD", toUpperCaseFirstLetter(columnName));
				args.put("COLUMN", columnName);
				args.put("TYPE", classNameString);
				args.put("CAST", cast);

				String commentBase = buildColumnComment(column);
				args.put("COMMENT_1", decorate(commentBase, createIndent(1)));
				args.put("COMMENT_2", decorate(commentBase, createIndent(2)));

				args.put("NULL_CHECK", nullCheck);
				args.put("RETURN_TYPE", returnType);
				args.put("PREFIX", returnPrefix);
				args.put("SUFFIX", returnSuffix);
				args.put("OPTIONAL", Boolean.toString(returnOptional));

				args.put("DB_TYPE", Integer.toString(columnMetadata.getType()));
				args.put("TYPE_NAME", columnMetadata.getTypeName());
				args.put("SIZE", Integer.toString(columnMetadata.getSize()));
				args.put("HAS_DECIMAL_DIGITS", Boolean.toString(columnMetadata.hasDecimalDigits()));
				args.put("DECIMAL_DIGITS", Integer.toString(columnMetadata.getDecimalDigits()));
				args.put("REMARKS", escape(columnMetadata.getRemarks()));

				String defaultValue = columnMetadata.getDefaultValue();
				args.put("DEFAULT", defaultValue == null ? null : escape(defaultValue));

				args.put("ORDINAL_POSITION", Integer.toString(columnMetadata.getOrdinalPosition()));

				args.put("NOT_NULL", String.valueOf(notNull));

				columnNames.add(
					codeFormatter.formatColumnNamesPart(columnNamesPartTemplate, args));

				properties.add(
					codeFormatter.formatRowPropertyAccessorPart(rowPropertyAccessorPartTemplate, args));

				list1.add(
					codeFormatter.formatRelationshipColumnPart1(relationshipColumnPart1Template, args));

				list2.add(
					codeFormatter.formatRelationshipColumnPart2(relationshipColumnPart2Template, args));
			}

			columnNamesPart = String.join("", columnNames);
			propertyAccessorPart = String.join("", properties);
			columnPart1 = String.join("", list1);
			columnPart2 = String.join("", list2);
		}

		String primaryKeyPart;
		{
			PrimaryKeyMetadata primaryKey = metadata.getPrimaryKeyMetadata(relation.getTablePath());
			String[] columns = primaryKey.getColumnNames();

			if (columns.length > 0) {
				Map<String, String> args = new HashMap<>();
				args.put("PK", primaryKey.getName());
				args.put("PK_COLUMNS", "\"" + String.join("\", \"", primaryKey.getColumnNames()) + "\"");

				args.put("PSEUDO", primaryKey.isPseudo() ? ", pseudo = true" : "");

				primaryKeyPart = codeFormatter.formatPrimaryKeyPart(primaryKeyPartTemplate, args);
				importPart.add(buildImportPart(PrimaryKey.class));
			} else {
				primaryKeyPart = "";
			}
		}

		String foreignKeysPart, rowRelationshipPart, myTemplate, tableRelationshipPart;
		{
			Map<String, Boolean> checker = createDuprecateChecker(relation);

			List<String> relationships = new LinkedList<>();
			List<String> rowRelationships = new LinkedList<>();
			List<String> tableRelationships = new LinkedList<>();

			for (Relationship child : relation.getRelationships()) {
				CrossReference crossReference = child.getCrossReference();
				String foreignKey = crossReference.getForeignKeyName();

				TablePath childPath = child.getTablePath();
				String childTableName = childPath.getTableName();

				String methodName = "$" + (checker.get(childTableName) ? childTableName + "$" + foreignKey : childTableName);

				String relationship = "$" + (checker.get(childTableName) ? childTableName + "$" + foreignKey : childTableName);

				String typeParam = Many.class.getSimpleName() + "<" + packageName + "." + tableName + ".Row, M>";

				Map<String, String> args = new HashMap<>();
				args.put("PACKAGE", packageName);
				args.put("TABLE", tableName);
				args.put("REFERENCE_PACKAGE", rootPackageName + "." + TableFacadePackageRule.care(childPath.getSchemaName()));

				String reference = childPath.toString();
				args.put("REFERENCE", childTableName);
				args.put("REFERENCE_PATH", reference);
				args.put("REFERENCE_FIELD", reference.replaceAll("\\.", "\\$"));

				args.put("FK", foreignKey);

				String[] fkColumns = crossReference.getForeignKeyColumnNames();
				args.put("FK_COLUMNS", String.join(", ", fkColumns));
				args.put("ANNOTATION_FK_COLUMNS", "\"" + String.join("\", \"", fkColumns) + "\"");

				args.put("REF_COLUMNS", "\"" + String.join("\", \"", crossReference.getPrimaryKeyColumnNames()) + "\"");

				args.put("PSEUDO", crossReference.isPseudo() ? ", pseudo = true" : "");

				args.put("METHOD", methodName);
				args.put("RELATIONSHIP", relationship);
				args.put("MANY", typeParam);

				relationships.add(
					codeFormatter.formatForeignKeysPart(
						foreignKeysPartTemplate,
						args));

				rowRelationships.add(
					codeFormatter.formatRowRelationshipPart(
						rowRelationshipPartTemplate,
						args));

				tableRelationships.add(
					codeFormatter.formatTableRelationshipPart(
						tableRelationshipPartTemplate,
						args));
			}

			if (relationships.size() > 0) {
				importPart.add(buildImportPart(ForeignKey.class));
				importPart.add(buildImportPart(Many.class));
			}

			myTemplate = Formatter.erase(template, relationships.isEmpty());

			foreignKeysPart = String.join("", relationships);
			rowRelationshipPart = String.join("", rowRelationships);
			tableRelationshipPart = String.join("", tableRelationships);
		}

		Map<String, String> args = new HashMap<>();
		args.put("PACKAGE", packageName);
		args.put("SCHEMA", schemaName);
		args.put("TABLE", tableName);
		args.put("IMPORTS", String.join(U.LINE_SEPARATOR, importPart));
		args.put("PARENT", tableFacadeSuperclass.getName());
		args.put("COLUMN_NAMES_PART", columnNamesPart);
		args.put("PRIMARY_KEY_PART", primaryKeyPart);
		args.put("FOREIGN_KEYS_PART", foreignKeysPart);
		args.put("ROW_PARENT", rowSuperclass.getName());
		args.put("ROW_PROPERTY_ACCESSOR_PART", propertyAccessorPart);
		args.put("ROW_RELATIONSHIP_PART", rowRelationshipPart);
		args.put("COLUMN_PART1", columnPart1);
		args.put("COLUMN_PART2", columnPart2);
		args.put("TABLE_RELATIONSHIP_PART", tableRelationshipPart);

		TableMetadata tableMetadata = metadata.getTableMetadata(target);

		args.put("TABLE_COMMENT", buildTableComment(tableMetadata, target));

		args.put("TYPE", tableMetadata.getType());
		args.put("REMARKS", escape(tableMetadata.getRemarks()));

		return codeFormatter.format(myTemplate, args);
	}

	@Override
	public String toString() {
		return U.toString(this);
	}

	private static String buildImportPart(Class<?> target) {
		return "import " + target.getName() + ";";
	}

	private static String escape(String string) {
		if (!U.presents(string)) return "";

		StringBuilder builder = new StringBuilder();

		string.chars().forEach(c -> {
			switch (c) {
			case '\t':
				builder.append("\\t");
				break;
			case '\b':
				builder.append("\\b");
				break;
			case '\n':
				builder.append("\\n");
				break;
			case '\r':
				builder.append("\\r");
				break;
			case '\f':
				builder.append("\\f");
				break;
			case '\"':
				builder.append("\\\"");
				break;
			case '\\':
				builder.append("\\\\");
				break;
			default:
				builder.append((char) c);
			}
		});

		return builder.toString();
	}

	private static Map<String, Boolean> createDuprecateChecker(Relationship relation) {
		Map<String, Boolean> checker = new HashMap<>();
		for (Relationship child : relation.getRelationships()) {
			String tableName = child.getTablePath().getTableName();
			if (checker.containsKey(tableName)) {
				checker.put(tableName, true);
			} else {
				checker.put(tableName, false);
			}
		}

		return checker;
	}

	private static Class<?> convertPrimitiveClassToWrapperClass(Class<?> target) {
		if (!target.isPrimitive()) return target;
		return primitiveToWrapperMap.get(target);
	}

	private Class<?> convertForNumber(Class<?> target) {
		if (!useNumberClass) return target;

		if (Number.class.isAssignableFrom(target)) return Number.class;

		return target;
	}

	private static String toUpperCaseFirstLetter(String target) {
		return Character.toUpperCase(target.charAt(0)) + target.substring(1);
	}

	private static void write(
		File java,
		String body,
		Charset charset)
		throws IOException {
		try (BufferedWriter writer = new BufferedWriter(
			new OutputStreamWriter(
				new FileOutputStream(java),
				charset))) {
			writer.write(body);
			writer.flush();
		}
	}

	private static String buildTableComment(
		TableMetadata tableMetadata,
		TablePath target) {
		StringBuilder builder = new StringBuilder();
		builder.append("schema: " + target.getSchemaName());
		builder.append(U.LINE_SEPARATOR);
		builder.append("name: " + tableMetadata.getName());
		builder.append(U.LINE_SEPARATOR);
		builder.append("type: " + tableMetadata.getType());
		builder.append(U.LINE_SEPARATOR);
		builder.append("remarks: ");
		builder.append(U.trim(tableMetadata.getRemarks()));

		return decorate(builder.toString(), "");
	}

	private static String buildColumnComment(Column column) {
		ColumnMetadata metadata = column.getColumnMetadata();

		StringBuilder builder = new StringBuilder();
		builder.append("name: " + metadata.getName());
		builder.append(U.LINE_SEPARATOR);
		builder.append("remarks: ");
		builder.append(U.trim(metadata.getRemarks()));
		builder.append(U.LINE_SEPARATOR);

		boolean hasDecimalDigits = metadata.hasDecimalDigits() && metadata.getDecimalDigits() != 0 ? true : false;

		String sizeString = "("
			+ metadata.getSize()
			+ (hasDecimalDigits ? ", " + metadata.getDecimalDigits() : "")
			+ ")";

		builder.append("type: " + metadata.getTypeName() + sizeString);
		builder.append(U.LINE_SEPARATOR);

		builder.append("not null: " + metadata.isNotNull());

		return builder.toString();
	}

	private static String decorate(String base, String top) {
		String prefix = top + " * ";
		String suffix = "<br>";
		String[] lines = base.split("[\\r\\n]+");
		return prefix + String.join(suffix + U.LINE_SEPARATOR + prefix, lines) + suffix;
	}

	private static String[] pickupFromSource(String source, String key) {
		String patternBase = "/\\*==" + key + "==\\*/";
		Matcher matcher = Pattern.compile(
			patternBase + "(.+?)" + patternBase,
			Pattern.MULTILINE + Pattern.DOTALL).matcher(source);
		matcher.find();
		return new String[] { matcher.group(1), matcher.replaceAll("") };
	}

	/**
	 * 使用できない名前の検査
	 */
	private static void checkName(String name) {
		if (!isGeneratableTableName(name)) throw new IllegalStateException(name);
	}

	private static String safe(String name) {
		if (!SourceVersion.isName(name)) return "_" + name;
		return name;
	}
}
