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
import javax.lang.model.util.SimpleElementVisitor8;
import javax.tools.Diagnostic.Kind;

import org.blendee.codegen.Formatter;
import org.blendee.util.SQLProxyBuilder;
import org.blendee.util.annotation.SQLProxy;

/**
 * @author 千葉 哲嗣
 */
@SupportedAnnotationTypes("org.blendee.util.annotation.SQLProxy")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class SQLProxyProcessor extends AbstractProcessor {

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

					MethodVisitor visitor = new MethodVisitor();
					e.getEnclosedElements().forEach(enc -> {
						enc.accept(visitor, infos);
					});

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
		TypeElement type = element.accept(new TypeVisitor(), null);
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

	private class TypeVisitor extends SimpleElementVisitor8<TypeElement, Void> {

		@Override
		protected TypeElement defaultAction(Element e, Void p) {
			throw new ProcessException();
		}

		@Override
		public TypeElement visitType(TypeElement e, Void p) {
			return e;
		}
	}

	private class MethodVisitor extends SimpleElementVisitor8<Void, List<MethodInfo>> {

		@Override
		public Void visitExecutable(ExecutableElement e, List<MethodInfo> p) {
			if (e.getModifiers().contains(Modifier.DEFAULT)) {
				error("cannot use default method", e);
				throw new ProcessException();
			}

			MethodInfo info = new MethodInfo();

			info.name = e.getSimpleName().toString();
			e.getParameters().forEach(parameter -> {
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
