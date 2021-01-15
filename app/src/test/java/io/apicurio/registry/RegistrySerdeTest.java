/*
 * Copyright 2020 Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.registry;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.apicurio.registry.client.RegistryService;
import io.apicurio.registry.rest.beans.ArtifactMetaData;
import io.apicurio.registry.support.TestCmmn;
import io.apicurio.registry.support.Tester;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.utils.ConcurrentUtil;
import io.apicurio.registry.utils.IoUtil;
import io.apicurio.registry.utils.serde.AbstractKafkaSerDe;
import io.apicurio.registry.utils.serde.AbstractKafkaSerializer;
import io.apicurio.registry.utils.serde.AbstractKafkaStrategyAwareSerDe;
import io.apicurio.registry.utils.serde.AvroEncoding;
import io.apicurio.registry.utils.serde.AvroKafkaDeserializer;
import io.apicurio.registry.utils.serde.AvroKafkaSerializer;
import io.apicurio.registry.utils.serde.ProtobufKafkaDeserializer;
import io.apicurio.registry.utils.serde.ProtobufKafkaSerializer;
import io.apicurio.registry.utils.serde.avro.AvroDatumProvider;
import io.apicurio.registry.utils.serde.avro.DefaultAvroDatumProvider;
import io.apicurio.registry.utils.serde.avro.ReflectAvroDatumProvider;
import io.apicurio.registry.utils.serde.strategy.AutoRegisterIdStrategy;
import io.apicurio.registry.utils.serde.strategy.CachedSchemaIdStrategy;
import io.apicurio.registry.utils.serde.strategy.FindBySchemaIdStrategy;
import io.apicurio.registry.utils.serde.strategy.FindLatestIdStrategy;
import io.apicurio.registry.utils.serde.strategy.GetOrCreateIdStrategy;
import io.apicurio.registry.utils.serde.strategy.GlobalIdStrategy;
import io.apicurio.registry.utils.serde.strategy.TopicRecordIdStrategy;
import io.apicurio.registry.utils.serde.util.HeaderUtils;
import io.apicurio.registry.utils.tests.RegistryServiceTest;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static io.apicurio.registry.utils.tests.TestUtils.retry;
import static io.apicurio.registry.utils.tests.TestUtils.waitForSchema;

/**
 * @author Ales Justin
 */
@QuarkusTest
public class RegistrySerdeTest extends AbstractResourceTestBase {

    @RegistryServiceTest
    public void testFindBySchema(Supplier<RegistryService> supplier) throws Exception {
        RegistryService client = supplier.get();

        String artifactId = generateArtifactId();
        Schema schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"myrecord3\",\"fields\":[{\"name\":\"bar\",\"type\":\"string\"}]}");
        CompletionStage<ArtifactMetaData> csa = client.createArtifact(ArtifactType.AVRO, artifactId, null, new ByteArrayInputStream(schema.toString().getBytes(StandardCharsets.UTF_8)));
        ArtifactMetaData amd = ConcurrentUtil.result(csa);

        this.waitForGlobalId(amd.getGlobalId());

        Assertions.assertNotNull(client.getArtifactMetaDataByGlobalId(amd.getGlobalId()));
        GlobalIdStrategy<Schema> idStrategy = new FindBySchemaIdStrategy<>();
        Assertions.assertEquals(amd.getGlobalId(), idStrategy.findId(client, artifactId, ArtifactType.AVRO, schema));
    }

    @RegistryServiceTest
    public void testGetOrCreate(Supplier<RegistryService> supplier) throws Exception {
        RegistryService client = supplier.get();

        Schema schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"myrecord3\",\"fields\":[{\"name\":\"bar\",\"type\":\"string\"}]}");
        String artifactId = generateArtifactId();
        byte[] schemaContent = IoUtil.toBytes(schema.toString());
        CompletionStage<ArtifactMetaData> csa = client.createArtifact(ArtifactType.AVRO, artifactId, null, new ByteArrayInputStream(schemaContent));
        ArtifactMetaData amd = ConcurrentUtil.result(csa);

        this.waitForGlobalId(amd.getGlobalId());

        Assertions.assertNotNull(client.getArtifactMetaDataByGlobalId(amd.getGlobalId()));
        GlobalIdStrategy<Schema> idStrategy = new GetOrCreateIdStrategy<>();
        Assertions.assertEquals(amd.getGlobalId(), idStrategy.findId(client, artifactId, ArtifactType.AVRO, schema));

        artifactId = generateArtifactId(); // new
        long id = idStrategy.findId(client, artifactId, ArtifactType.AVRO, schema);

        this.waitForGlobalId(id);

        Assertions.assertEquals(id, idStrategy.findId(client, artifactId, ArtifactType.AVRO, schema));
    }

    @RegistryServiceTest
    public void testCachedSchema(Supplier<RegistryService> supplier) throws Exception {
        RegistryService service = supplier.get();

        Schema schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"myrecord5x\",\"fields\":[{\"name\":\"bar\",\"type\":\"string\"}]}");
        String artifactId = generateArtifactId();

        GlobalIdStrategy<Schema> idStrategy = new CachedSchemaIdStrategy<>();
        long id = idStrategy.findId(service, artifactId, ArtifactType.AVRO, schema);
        service.reset();

        retry(() -> service.getArtifactMetaDataByGlobalId(id));

        Assertions.assertEquals(id, idStrategy.findId(service, artifactId, ArtifactType.AVRO, schema));
    }

    @RegistryServiceTest
    public void testCheckPeriod(Supplier<RegistryService> supplier) throws Exception {
        RegistryService service = supplier.get();

        Schema schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"myrecord5x\",\"fields\":[{\"name\":\"bar\",\"type\":\"string\"}]}");
        String artifactId = generateArtifactId();
        byte[] schemaContent = IoUtil.toBytes(schema.toString());
        CompletionStage<ArtifactMetaData> csa = service.createArtifact(ArtifactType.AVRO, artifactId, null, new ByteArrayInputStream(schemaContent));
        ArtifactMetaData amd = ConcurrentUtil.result(csa);
        this.waitForGlobalId(amd.getGlobalId());

        long pc = 5000L; // 5seconds check period ...

        Map<String, Object> config = new HashMap<>();
        config.put(AbstractKafkaStrategyAwareSerDe.REGISTRY_CHECK_PERIOD_MS_CONFIG_PARAM, String.valueOf(pc));
        GlobalIdStrategy<Schema> idStrategy = new FindLatestIdStrategy<>();
        idStrategy.configure(config, false);

        long id1 = idStrategy.findId(service, artifactId, ArtifactType.AVRO, schema);
        service.reset();
        long id2 = idStrategy.findId(service, artifactId, ArtifactType.AVRO, schema);
        service.reset();
        Assertions.assertEquals(id1, id2); // should be less than 5seconds ...
        retry(() -> service.getArtifactMetaDataByGlobalId(id2));

        service.updateArtifact(artifactId, ArtifactType.AVRO, new ByteArrayInputStream(schemaContent));
        Thread.sleep(pc + 1);
        retry(() -> Assertions.assertNotEquals(id2, service.getArtifactMetaData(artifactId).getGlobalId()));

        Assertions.assertNotEquals(id2, idStrategy.findId(service, artifactId, ArtifactType.AVRO, schema));
    }

    @SuppressWarnings("unchecked")
    @RegistryServiceTest
    public void testConfiguration(Supplier<RegistryService> supplier) throws Exception {
        Schema schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"myrecord3\",\"fields\":[{\"name\":\"bar\",\"type\":\"string\"}]}");

        String artifactId = generateArtifactId();

        CompletionStage<ArtifactMetaData> csa = supplier.get().createArtifact(
            ArtifactType.AVRO,
            artifactId + "-myrecord3",
            null, 
            new ByteArrayInputStream(schema.toString().getBytes(StandardCharsets.UTF_8))
        );
        ArtifactMetaData amd = ConcurrentUtil.result(csa);
        // reset any cache
        supplier.get().reset();
        // wait for global id store to populate (in case of Kafka / Streams)
        ArtifactMetaData amdById = retry(() -> supplier.get().getArtifactMetaDataByGlobalId(amd.getGlobalId()));
        Assertions.assertNotNull(amdById);

        GenericData.Record record = new GenericData.Record(schema);
        record.put("bar", "somebar");

        Map<String, Object> config = new HashMap<>();
        config.put(AbstractKafkaSerDe.REGISTRY_URL_CONFIG_PARAM, "http://localhost:8081/api");
        config.put(AbstractKafkaSerializer.REGISTRY_ARTIFACT_ID_STRATEGY_CONFIG_PARAM, new TopicRecordIdStrategy());
        config.put(AbstractKafkaSerializer.REGISTRY_GLOBAL_ID_STRATEGY_CONFIG_PARAM, new FindLatestIdStrategy<>());
        config.put(AvroDatumProvider.REGISTRY_AVRO_DATUM_PROVIDER_CONFIG_PARAM, new DefaultAvroDatumProvider<>());
        Serializer<GenericData.Record> serializer = (Serializer<GenericData.Record>) getClass().getClassLoader()
                                                                                               .loadClass(AvroKafkaSerializer.class.getName())
                                                                                               .newInstance();
        serializer.configure(config, true);
        byte[] bytes = serializer.serialize(artifactId, record);

        Deserializer<GenericData.Record> deserializer = (Deserializer<GenericData.Record>) getClass().getClassLoader()
                                                                                                     .loadClass(AvroKafkaDeserializer.class.getName())
                                                                                                     .newInstance();
        deserializer.configure(config, true);

        record = deserializer.deserialize(artifactId, bytes);
        Assertions.assertEquals("somebar", record.get("bar").toString());

        config.put(AbstractKafkaSerializer.REGISTRY_ARTIFACT_ID_STRATEGY_CONFIG_PARAM, TopicRecordIdStrategy.class);
        config.put(AbstractKafkaSerializer.REGISTRY_GLOBAL_ID_STRATEGY_CONFIG_PARAM, FindLatestIdStrategy.class);
        config.put(AvroDatumProvider.REGISTRY_AVRO_DATUM_PROVIDER_CONFIG_PARAM, DefaultAvroDatumProvider.class);
        serializer.configure(config, true);
        bytes = serializer.serialize(artifactId, record);
        deserializer.configure(config, true);
        record = deserializer.deserialize(artifactId, bytes);
        Assertions.assertEquals("somebar", record.get("bar").toString());

        config.put(AbstractKafkaSerializer.REGISTRY_ARTIFACT_ID_STRATEGY_CONFIG_PARAM, TopicRecordIdStrategy.class.getName());
        config.put(AbstractKafkaSerializer.REGISTRY_GLOBAL_ID_STRATEGY_CONFIG_PARAM, FindLatestIdStrategy.class.getName());
        config.put(AvroDatumProvider.REGISTRY_AVRO_DATUM_PROVIDER_CONFIG_PARAM, DefaultAvroDatumProvider.class.getName());
        serializer.configure(config, true);
        bytes = serializer.serialize(artifactId, record);
        deserializer.configure(config, true);
        record = deserializer.deserialize(artifactId, bytes);
        Assertions.assertEquals("somebar", record.get("bar").toString());

        serializer.close();
        deserializer.close();
    }

    @RegistryServiceTest
    public void testAvro(Supplier<RegistryService> supplier) throws Exception {
        Schema schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"myrecord3\",\"fields\":[{\"name\":\"bar\",\"type\":\"string\"}]}");
        try (AvroKafkaSerializer<GenericData.Record> serializer = new AvroKafkaSerializer<>(supplier.get());
             Deserializer<GenericData.Record> deserializer = new AvroKafkaDeserializer<>(supplier.get())) {

            serializer.setGlobalIdStrategy(new AutoRegisterIdStrategy<>());
            
            GenericData.Record record = new GenericData.Record(schema);
            record.put("bar", "somebar");

            String subject = generateArtifactId();

            byte[] bytes = serializer.serialize(subject, record);

            // some impl details ...
            waitForSchema(supplier.get(), bytes);

            GenericData.Record ir = deserializer.deserialize(subject, bytes);

            Assertions.assertEquals("somebar", ir.get("bar").toString());
        }
    }

    @RegistryServiceTest
    public void testAvroJSON(Supplier<RegistryService> supplier) throws Exception {
        Schema schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"myrecord3\",\"fields\":[{\"name\":\"bar\",\"type\":\"string\"}]}");
        try (AvroKafkaSerializer<GenericData.Record> serializer = new AvroKafkaSerializer<>(supplier.get());
             Deserializer<GenericData.Record> deserializer = new AvroKafkaDeserializer<>(supplier.get())) {
            HashMap<String, String> config = new HashMap<>();
            config.put(AvroEncoding.AVRO_ENCODING, AvroEncoding.AVRO_JSON);
            serializer.configure(config,false);
            deserializer.configure(config, false);

            serializer.setGlobalIdStrategy(new AutoRegisterIdStrategy<>());

            GenericData.Record record = new GenericData.Record(schema);
            record.put("bar", "somebar");

            String subject = generateArtifactId();

            byte[] bytes = serializer.serialize(subject, record);

            // Test msg is stored as json, take 1st 9 bytes off (magic byte and long)
            JSONObject msgAsJson = new JSONObject(new String(Arrays.copyOfRange(bytes, 9, bytes.length)));
            Assertions.assertEquals("somebar", msgAsJson.getString("bar"));
            
            // some impl details ...
            waitForSchema(supplier.get(), bytes);

            GenericData.Record ir = deserializer.deserialize(subject, bytes);

            Assertions.assertEquals("somebar", ir.get("bar").toString());
        }
    }

    @RegistryServiceTest
    public void testAvroUsingHeaders(Supplier<RegistryService> supplier) throws Exception {
        Schema schema = new Schema.Parser().parse("{\"type\":\"record\",\"name\":\"myrecord3\",\"fields\":[{\"name\":\"bar\",\"type\":\"string\"}]}");
        try (AvroKafkaSerializer<GenericData.Record> serializer = new AvroKafkaSerializer<>(supplier.get());
             Deserializer<GenericData.Record> deserializer = new AvroKafkaDeserializer<>(supplier.get())) {

            serializer.setGlobalIdStrategy(new AutoRegisterIdStrategy<>());
            HashMap<String, String> config = new HashMap<>();
            config.put(AbstractKafkaSerDe.USE_HEADERS, "true");
            serializer.configure(config,false);
            deserializer.configure(config, false);

            GenericData.Record record = new GenericData.Record(schema);
            record.put("bar", "somebar");

            String subject = generateArtifactId();
            Headers headers = new RecordHeaders();
            byte[] bytes = serializer.serialize(subject, headers, record);
            Assertions.assertNotNull(headers.lastHeader(HeaderUtils.DEFAULT_HEADER_VALUE_GLOBAL_ID));
            Header globalId =  headers.lastHeader(HeaderUtils.DEFAULT_HEADER_VALUE_GLOBAL_ID);
            long id = ByteBuffer.wrap(globalId.value()).getLong();

            // wait for schema to be created
            supplier.get().reset();
            ArtifactMetaData amd = retry(() -> supplier.get().getArtifactMetaDataByGlobalId(id));
            Assertions.assertNotNull(amd);



            GenericData.Record ir = deserializer.deserialize(subject, headers, bytes);

            Assertions.assertEquals("somebar", ir.get("bar").toString());
        }
    }

    @RegistryServiceTest
    public void testAvroReflect(Supplier<RegistryService> supplier) throws Exception {
        try (AvroKafkaSerializer<Tester> serializer = new AvroKafkaSerializer<>(supplier.get());
             AvroKafkaDeserializer<Tester> deserializer = new AvroKafkaDeserializer<>(supplier.get())) {

            serializer.setGlobalIdStrategy(new AutoRegisterIdStrategy<>());
            serializer.setAvroDatumProvider(new ReflectAvroDatumProvider<>());
            deserializer.setAvroDatumProvider(new ReflectAvroDatumProvider<>());
            
            String artifactId = generateArtifactId();

            Tester tester = new Tester("Apicurio");
            byte[] bytes = serializer.serialize(artifactId, tester);

            waitForSchema(supplier.get(), bytes);

            tester = deserializer.deserialize(artifactId, bytes);

            Assertions.assertEquals("Apicurio", tester.getName());
        }
    }

    @RegistryServiceTest
    public void testProto(Supplier<RegistryService> supplier) throws Exception {
        try (ProtobufKafkaSerializer<TestCmmn.UUID> serializer = new ProtobufKafkaSerializer<TestCmmn.UUID>(supplier.get());
             Deserializer<DynamicMessage> deserializer = new ProtobufKafkaDeserializer(supplier.get())) {

            serializer.setGlobalIdStrategy(new AutoRegisterIdStrategy<>());

            TestCmmn.UUID record = TestCmmn.UUID.newBuilder().setLsb(2).setMsb(1).build();

            String subject = generateArtifactId();

            byte[] bytes = serializer.serialize(subject, record);

            waitForSchema(supplier.get(), bytes);

            DynamicMessage dm = deserializer.deserialize(subject, bytes);
            Descriptors.Descriptor descriptor = dm.getDescriptorForType();

            Descriptors.FieldDescriptor lsb = descriptor.findFieldByName("lsb");
            Assertions.assertNotNull(lsb);
            Assertions.assertEquals(2L, dm.getField(lsb));

            Descriptors.FieldDescriptor msb = descriptor.findFieldByName("msb");
            Assertions.assertNotNull(msb);
            Assertions.assertEquals(1L, dm.getField(msb));
        }
    }
}
