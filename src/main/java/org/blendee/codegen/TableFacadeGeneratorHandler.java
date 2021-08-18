package org.blendee.codegen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.blendee.jdbc.TablePath;
import org.blendee.sql.Relationship;
import org.blendee.sql.RelationshipFactory;

@SuppressWarnings("javadoc")
public abstract class TableFacadeGeneratorHandler {

	private final Set<TablePath> tables = new LinkedHashSet<>();

	public void add(TablePath table) {
		tables.add(table);
	}

	protected abstract boolean exists(TablePath path);

	protected abstract void start(TablePath path);

	protected abstract boolean exists();

	protected abstract void infoSkip();

	protected abstract String format(String source);

	protected abstract String loadSource();

	protected abstract void writeSource(String source);

	protected abstract void end();

	protected abstract Path getOutputRoot();

	public void execute(TableFacadeGenerator generator) throws IOException {
		var factory = RelationshipFactory.getInstance();

		while (tables.size() > 0) {
			//popの代わり
			var path = tables.stream().findFirst().get();
			tables.remove(path);

			start(path);
			try {
				var relationship = factory.getInstance(path);

				var newSource = format(generator.build(relationship));

				if (exists()) {
					var oldSource = loadSource();

					if (oldSource.equals(newSource)) {
						infoSkip();

						continue;
					}
				}

				writeSource(newSource);

				collect(tables, relationship);

				//大量のテーブルを一度に実行したときのための節約クリア
				//Metadataはキャッシュを使用しているので、同じテーブルを処理してもDBから再取得はしない
				factory.clearCache();

			} finally {
				end();
			}
		}

		generator.writeDatabaseInfo(getOutputRoot());
	}

	private void collect(Set<TablePath> tables, Relationship relation) {
		for (var child : relation.getRelationships()) {
			var childPath = child.getTablePath();
			if (!tables.contains(childPath) && !exists(childPath)) tables.add(childPath);
		}
	}
}
