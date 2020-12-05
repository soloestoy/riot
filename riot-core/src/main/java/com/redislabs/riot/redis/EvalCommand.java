package com.redislabs.riot.redis;

import java.util.Map;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.redis.EvalItemWriter;
import org.springframework.core.convert.converter.Converter;

import com.redislabs.riot.convert.MapToStringArrayConverter;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulConnection;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "evalsha")
public class EvalCommand extends AbstractRedisCommand<Map<String, Object>> {

	@Option(names = "--sha", description = "Digest", paramLabel = "<sha>")
	private String sha;

	@Option(names = "--keys", arity = "1..*", description = "Key fields", paramLabel = "<names>")
	private String[] keys = new String[0];

	@Option(names = "--args", arity = "1..*", description = "Arg fields", paramLabel = "<names>")
	private String[] args = new String[0];

	@Option(names = "--output", description = "Output: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})", paramLabel = "<type>")
	private ScriptOutputType outputType = ScriptOutputType.STATUS;

	@Override
	public ItemWriter<Map<String, Object>> writer(AbstractRedisClient client,
			GenericObjectPoolConfig<StatefulConnection<String, String>> poolConfig) throws Exception {
		return EvalItemWriter.<Map<String, Object>>builder().client(client).poolConfig(poolConfig).sha(sha)
				.outputType(outputType).keysConverter(mapToArrayConverter(keys))
				.argsConverter(mapToArrayConverter(args)).build();
	}

	@SuppressWarnings("unchecked")
	private MapToStringArrayConverter mapToArrayConverter(String[] fields) {
		Converter<Map<String, Object>, String>[] extractors = new Converter[fields.length];
		for (int index = 0; index < fields.length; index++) {
			extractors[index] = stringFieldExtractor(fields[index]);
		}
		return new MapToStringArrayConverter(extractors);
	}

}
