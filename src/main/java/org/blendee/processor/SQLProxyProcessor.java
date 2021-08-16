package org.blendee.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleElementVisitor8;
import javax.lang.model.util.SimpleTypeVisitor8;
import javax.tools.Diagnostic.Kind;

import org.blendee.codegen.Formatter;
import org.blendee.jdbc.BResultSet;
import org.blendee.jdbc.DataTypeConverter;
import org.blendee.util.SQLProxyBuilder;
import org.blendee.util.annotation.SQLProxy;

/**
 * @author 千葉 哲嗣
 */
@SupportedAnnotationTypes("org.blendee.util.annotation.SQLProxy")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class SQLProxyProcessor extends AbstractProcessor {

	private static class TypeVisitor extends SimpleElementVisitor8<TypeElement, Void> {

		@Override
		protected TypeElement defaultAction(Element e, Void p) {
			throw new ProcessException();
		}

		@Override
		public TypeElement visitType(TypeElement e, Void p) {
			return e;
		}
	}

	private static final TypeVisitor typeVisitor = new TypeVisitor();

	private static final ThreadLocal<Boolean> hasError = ThreadLocal.withInitial(() -> false);

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (annotations.size() == 0)
			return false;

		try {
			annotations.forEach(a -> {
				roundEnv.getElementsAnnotatedWith(a).forEach(e -> {
					ElementKind kind = e.getKind();
					if (kind != ElementKind.INTERFACE) {
						error("cannot annotate" + kind.name() + " with " + SQLProxy.class.getSimpleName(), e);

						throw new ProcessException();
					}

					List<MethodInfo> infos = new LinkedList<>();

					hasError.set(false);

					MethodVisitor visitor = new MethodVisitor();
					e.getEnclosedElements().forEach(enc -> {
						enc.accept(visitor, infos);
					});

					if (hasError.get()) {
						return;
					}

					String template = Formatter.readTemplate(SQLProxyMetadataTemplate.class, "UTF-8");
					template = Formatter.convertToTemplate(template);

					Map<String, String> param = new HashMap<>();

					String packageName = packageName(e);

					String className = className(packageName, e) + SQLProxyBuilder.METADATA_CLASS_SUFFIX;

					param.put("PACKAGE", packageName.isEmpty() ? "" : ("package " + packageName + ";"));
					param.put("INTERFACE", className);

					String methodPart = buildMetodsPart(infos);

					template = Formatter.erase(template, methodPart.isEmpty());

					param.put("METHODS", methodPart);

					template = Formatter.format(template, param);

					String fileName = packageName.isEmpty() ? className : packageName + "." + className;
					try {
						try (Writer writer = super.processingEnv.getFiler().createSourceFile(fileName).openWriter()) {
							writer.write(template);
						}
					} catch (IOException ioe) {
						error(ioe.getMessage(), e);
					}

					info(fileName + " generated");
				});
			});
		} catch (ProcessException e) {
			return false;
		}

		return true;
	}

	private String packageName(Element e) {
		return super.processingEnv.getElementUtils().getPackageOf(e).getQualifiedName().toString();
	}

	private String className(String packageName, Element element) {
		TypeElement type = element.accept(typeVisitor, null);
		String name = type.getQualifiedName().toString();

		// . はインナークラスの区切りにも使用されるので、純粋にパッケージ名のみを取り除く
		name = name.substring(packageName.length());

		//パッケージに属している場合の先頭の . を除去
		name = name.replaceFirst("^\\.", "");

		//インナークラス名の場合の変換
		return name.replace('.', '$');
	}

	private static String buildMetodsPart(List<MethodInfo> infos) {
		return String.join(", ", infos.stream().map(SQLProxyProcessor::methodPart).collect(Collectors.toList()));
	}

	private static String methodPart(MethodInfo info) {
		String args = String.join(", ", info.parameterNames.stream().map(n -> "\"" + n + "\"").collect(Collectors.toList()));
		return "@Method(name = \"" + info.name + "\", args = {" + args + "})";
	}

	@SuppressWarnings("serial")
	private static class ProcessException extends RuntimeException {
	}

	private void info(String message) {
		super.processingEnv.getMessager().printMessage(Kind.NOTE, message);
	}

	private void error(String message, Element e) {
		super.processingEnv.getMessager().printMessage(Kind.ERROR, message, e);
	}

	private class ReturnTypeChecker extends SimpleTypeVisitor8<Void, ExecutableElement> {

		@Override
		protected Void defaultAction(TypeMirror e, ExecutableElement p) {
			error("cannot use return type [" + e + "]", p);
			hasError.set(true);
			return DEFAULT_VALUE;
		}

		@Override
		public Void visitPrimitive(PrimitiveType t, ExecutableElement p) {
			switch (t.getKind()) {
			case INT:
			case BOOLEAN:
				return DEFAULT_VALUE;
			default:
				return defaultAction(t, p);
			}
		}

		@Override
		public Void visitDeclared(DeclaredType t, ExecutableElement p) {
			TypeElement type = t.asElement().accept(typeVisitor, null);

			if (sameClass(type, BResultSet.class)) return DEFAULT_VALUE;
			if (sameClass(type, SQLProxy.ResultSet.class)) return DEFAULT_VALUE;

			return defaultAction(t, p);
		}

		@Override
		public Void visitNoType(NoType t, ExecutableElement p) {
			// void
			return DEFAULT_VALUE;
		}
	}

	private class ParameterTypeChecker extends SimpleTypeVisitor8<Void, VariableElement> {

		@Override
		protected Void defaultAction(TypeMirror e, VariableElement p) {
			error("cannot use parameter type [" + e + "]", p);
			hasError.set(true);
			return DEFAULT_VALUE;
		}

		@Override
		public Void visitPrimitive(PrimitiveType t, VariableElement p) {
			switch (t.getKind()) {
			case BOOLEAN:
			case BYTE:
			case DOUBLE:
			case FLOAT:
			case INT:
			case LONG:
				return DEFAULT_VALUE;
			default:
				return defaultAction(t, p);
			}
		}

		@Override
		public Void visitDeclared(DeclaredType t, VariableElement p) {
			TypeElement type = t.asElement().accept(typeVisitor, null);

			if (sameClass(type, DataTypeConverter.BIG_DECIMAL_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.BINARY_STREAM_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.BLOB_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.BYTE_ARRAY_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.CHARACTER_STREAM_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.CLOB_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.OBJECT_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.STRING_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.TIMESTAMP_TYPE)) return DEFAULT_VALUE;
			if (sameClass(type, DataTypeConverter.UUID_TYPE)) return DEFAULT_VALUE;

			return defaultAction(t, p);
		}
	}

	private static boolean sameClass(TypeElement type, Class<?> clazz) {
		return type.getQualifiedName().toString().equals(clazz.getCanonicalName());
	}

	private class MethodVisitor extends SimpleElementVisitor8<Void, List<MethodInfo>> {

		@Override
		public Void visitExecutable(ExecutableElement e, List<MethodInfo> p) {
			if (e.getModifiers().contains(Modifier.DEFAULT)) {
				error("cannot use default method", e);
				throw new ProcessException();
			}

			e.getReturnType().accept(new ReturnTypeChecker(), e);

			MethodInfo info = new MethodInfo();

			ParameterTypeChecker checker = new ParameterTypeChecker();

			info.name = e.getSimpleName().toString();
			e.getParameters().forEach(parameter -> {
				parameter.asType().accept(checker, parameter);

				info.parameterNames.add(parameter.getSimpleName().toString());
			});

			p.add(info);

			return DEFAULT_VALUE;
		}
	}

	private static class MethodInfo {

		private String name;

		private final List<String> parameterNames = new LinkedList<>();;
	}
}
