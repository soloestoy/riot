package com.redislabs.riot.cli.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.redislabs.riot.cli.RedisCommand;
import com.redislabs.riot.redis.writer.RedisMapWriter;
import com.redislabs.riot.redis.writer.CollectionMapWriter;
import com.redislabs.riot.redis.writer.EvalshaMapWriter;
import com.redislabs.riot.redis.writer.ExpireMapWriter;
import com.redislabs.riot.redis.writer.GeoaddMapWriter;
import com.redislabs.riot.redis.writer.HmsetMapWriter;
import com.redislabs.riot.redis.writer.LpushMapWriter;
import com.redislabs.riot.redis.writer.RedisDataStructureMapWriter;
import com.redislabs.riot.redis.writer.RpushMapWriter;
import com.redislabs.riot.redis.writer.SaddMapWriter;
import com.redislabs.riot.redis.writer.SetFieldMapWriter;
import com.redislabs.riot.redis.writer.SetMapWriter;
import com.redislabs.riot.redis.writer.SetObjectMapWriter;
import com.redislabs.riot.redis.writer.XaddIdMapWriter;
import com.redislabs.riot.redis.writer.XaddIdMaxlenMapWriter;
import com.redislabs.riot.redis.writer.XaddMapWriter;
import com.redislabs.riot.redis.writer.XaddMaxlenMapWriter;
import com.redislabs.riot.redis.writer.ZaddMapWriter;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisStreamAsyncCommands;
import picocli.CommandLine.Option;

public class RedisCommandOptions {

	@Option(names = "--zset-score", description = "Name of the field to use for sorted set scores", paramLabel = "<field>")
	private String zsetScore;
	@Option(names = "--zset-default-score", description = "Score when field not present (default: ${DEFAULT-VALUE})", paramLabel = "<float>")
	private double zsetDefaultScore = 1d;
	@Option(names = "--members", arity = "1..*", description = "Names of fields composing member ids in collection data structures (list, geo, set, zset)", paramLabel = "<names>")
	private String[] members = new String[0];
	@Option(names = "--expire-default-timeout", description = "Default timeout in seconds (default: ${DEFAULT-VALUE})", paramLabel = "<seconds>")
	private long expireDefaultTimeout = 60;
	@Option(names = "--expire-timeout", description = "Field to get the timeout value from", paramLabel = "<field>")
	private String expireTimeout;
	@Option(names = "--geo-lon", description = "Longitude field", paramLabel = "<field>")
	private String geoLon;
	@Option(names = "--geo-lat", description = "Latitude field", paramLabel = "<field>")
	private String geoLat;
	@Option(names = "--eval-sha", description = "SHA1 digest of the Lua script", paramLabel = "<string>")
	private String evalSha;
	@Option(names = "--eval-output", description = "Lua script output type: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})", paramLabel = "<type>")
	private ScriptOutputType outputType = ScriptOutputType.STATUS;
	@Option(names = "--eval-keys", arity = "1..*", description = "Fields for Lua script keys", paramLabel = "<field1,field2,...>")
	private String[] evalKeys = new String[0];
	@Option(names = "--eval-args", arity = "1..*", description = "Fields for Lua script args", paramLabel = "<field1,field2,...>")
	private String[] evalArgs = new String[0];
	@Option(names = "--xadd-trim", description = "Apply efficient trimming for capped streams using the ~ flag")
	private boolean xaddTrim;
	@Option(names = "--xadd-maxlen", description = "Limit stream to maxlen entries", paramLabel = "<int>")
	private Long xaddMaxlen;
	@Option(names = "--xadd-id", description = "Field used for stream entry IDs", paramLabel = "<field>")
	private String xaddId;
	@Option(names = "--string-format", description = "Serialization format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})", paramLabel = "<string>")
	private StringFormat stringFormat = StringFormat.json;
	@Option(names = "--string-root", description = "XML root element name", paramLabel = "<name>")
	private String stringRoot;
	@Option(names = "--string-value", description = "Field to use for value when using raw format", paramLabel = "<field>")
	private String stringValue;
	@Option(names = { "-c",
			"--command" }, description = "Redis command: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})", paramLabel = "<name>")
	private RedisCommand command = RedisCommand.hmset;

	private ZaddMapWriter zaddWriter() {
		ZaddMapWriter writer = new ZaddMapWriter();
		writer.setDefaultScore(zsetDefaultScore);
		writer.setScoreField(zsetScore);
		return writer;
	}

	private SetMapWriter setWriter() {
		switch (stringFormat) {
		case raw:
			SetFieldMapWriter fieldWriter = new SetFieldMapWriter();
			fieldWriter.setField(stringValue);
			return fieldWriter;
		case xml:
			SetObjectMapWriter xmlWriter = new SetObjectMapWriter();
			xmlWriter.setObjectWriter(objectWriter(new XmlMapper()));
			return xmlWriter;
		default:
			SetObjectMapWriter jsonWriter = new SetObjectMapWriter();
			jsonWriter.setObjectWriter(objectWriter(new ObjectMapper()));
			return jsonWriter;
		}
	}

	private ObjectWriter objectWriter(ObjectMapper mapper) {
		return mapper.writer().withRootName(stringRoot);
	}

	private RedisDataStructureMapWriter<RedisStreamAsyncCommands<String, String>> xaddWriter() {
		if (xaddId == null) {
			if (xaddMaxlen == null) {
				return new XaddMapWriter();
			}
			XaddMaxlenMapWriter writer = new XaddMaxlenMapWriter();
			writer.setApproximateTrimming(xaddTrim);
			writer.setMaxlen(xaddMaxlen);
			return writer;
		}
		if (xaddMaxlen == null) {
			XaddIdMapWriter writer = new XaddIdMapWriter();
			writer.setIdField(xaddId);
			return writer;
		}
		XaddIdMaxlenMapWriter writer = new XaddIdMaxlenMapWriter();
		writer.setApproximateTrimming(xaddTrim);
		writer.setIdField(xaddId);
		writer.setMaxlen(xaddMaxlen);
		return writer;
	}

	private EvalshaMapWriter evalshaWriter() {
		EvalshaMapWriter luaWriter = new EvalshaMapWriter();
		luaWriter.setArgs(evalArgs);
		luaWriter.setKeys(evalKeys);
		luaWriter.setOutputType(outputType);
		luaWriter.setSha(evalSha);
		return luaWriter;
	}

	private ExpireMapWriter expireWriter() {
		ExpireMapWriter expireWriter = new ExpireMapWriter();
		expireWriter.setDefaultTimeout(expireDefaultTimeout);
		expireWriter.setTimeoutField(expireTimeout);
		return expireWriter;
	}

	private GeoaddMapWriter geoWriter() {
		GeoaddMapWriter writer = new GeoaddMapWriter();
		writer.setLatitudeField(geoLat);
		writer.setLongitudeField(geoLon);
		return writer;
	}

	public RedisMapWriter<?> writer() {
		RedisMapWriter<?> redisItemWriter = redisItemWriter(command);
		if (redisItemWriter instanceof CollectionMapWriter) {
			((CollectionMapWriter<?>) redisItemWriter).setFields(members);
		}
		return redisItemWriter;
	}

	private RedisMapWriter<?> redisItemWriter(RedisCommand command) {
		switch (command) {
		case expire:
			return expireWriter();
		case geoadd:
			return geoWriter();
		case lpush:
			return new LpushMapWriter();
		case rpush:
			return new RpushMapWriter();
		case evalsha:
			return evalshaWriter();
		case sadd:
			return new SaddMapWriter();
		case xadd:
			return xaddWriter();
		case set:
			return setWriter();
		case zadd:
			return zaddWriter();
		default:
			return new HmsetMapWriter();
		}
	}

}