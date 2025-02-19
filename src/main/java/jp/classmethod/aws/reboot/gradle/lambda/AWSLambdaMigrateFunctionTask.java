/*
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jp.classmethod.aws.reboot.gradle.lambda;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import lombok.Getter;
import lombok.Setter;

import org.gradle.api.GradleException;
import org.gradle.api.internal.ConventionTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.model.CreateAliasRequest;
import com.amazonaws.services.lambda.model.CreateAliasResult;
import com.amazonaws.services.lambda.model.CreateFunctionRequest;
import com.amazonaws.services.lambda.model.CreateFunctionResult;
import com.amazonaws.services.lambda.model.Environment;
import com.amazonaws.services.lambda.model.FunctionCode;
import com.amazonaws.services.lambda.model.FunctionConfiguration;
import com.amazonaws.services.lambda.model.GetFunctionRequest;
import com.amazonaws.services.lambda.model.GetFunctionResult;
import com.amazonaws.services.lambda.model.ListTagsRequest;
import com.amazonaws.services.lambda.model.ListTagsResult;
import com.amazonaws.services.lambda.model.ResourceNotFoundException;
import com.amazonaws.services.lambda.model.Runtime;
import com.amazonaws.services.lambda.model.TagResourceRequest;
import com.amazonaws.services.lambda.model.UntagResourceRequest;
import com.amazonaws.services.lambda.model.UpdateAliasRequest;
import com.amazonaws.services.lambda.model.UpdateAliasResult;
import com.amazonaws.services.lambda.model.UpdateFunctionCodeRequest;
import com.amazonaws.services.lambda.model.UpdateFunctionCodeResult;
import com.amazonaws.services.lambda.model.UpdateFunctionConfigurationRequest;
import com.amazonaws.services.lambda.model.UpdateFunctionConfigurationResult;
import com.amazonaws.services.lambda.model.VpcConfig;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;

public class AWSLambdaMigrateFunctionTask extends ConventionTask {
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private String functionName;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private String role;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private Runtime runtime;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private String handler;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private String functionDescription;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private Integer lambdaTimeout;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private Integer memorySize;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private File zipFile;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private S3File s3File;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private VpcConfigWrapper vpc;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private Map<String, String> environment;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private Map<String, String> tags;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private List<String> layers;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private Boolean publish;
	
	@Getter(onMethod = @__(@Input))
	private CreateFunctionResult createFunctionResult;
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private String alias;
	
	
	public AWSLambdaMigrateFunctionTask() {
		setDescription("Create / Update Lambda function.");
		setGroup("AWS");
	}
	
	@TaskAction
	public void createOrUpdateFunction() throws FileNotFoundException, IOException {
		// to enable conventionMappings feature
		String functionName = getFunctionName();
		File zipFile = getZipFile();
		S3File s3File = getS3File();
		
		validateCreateOrUpdateFunctionVariables(functionName, zipFile, s3File);
		
		AWSLambdaPluginExtension ext = getProject().getExtensions().getByType(AWSLambdaPluginExtension.class);
		AWSLambda lambda = ext.getClient();
		
		try {
			GetFunctionResult getFunctionResult =
					lambda.getFunction(new GetFunctionRequest().withFunctionName(functionName));
			FunctionConfiguration config = getFunctionResult.getConfiguration();
			if (config == null) {
				config = new FunctionConfiguration().withRuntime(Runtime.Nodejs);
			}
			
			// for proper versioning, configuration needs to be updated first
			updateFunctionConfiguration(lambda, config);
			updateFunctionCode(lambda);
		} catch (ResourceNotFoundException e) {
			getLogger().warn(e.getMessage());
			getLogger().warn("Creating function... {}", functionName);
			createFunction(lambda);
		}
	}
	
	private void validateCreateOrUpdateFunctionVariables(String functionName, File zipFile, S3File s3File) {
		if (functionName == null) {
			throw new GradleException("functionName is required");
		}
		
		if ((zipFile == null && s3File == null) || (zipFile != null && s3File != null)) {
			throw new GradleException("exactly one of zipFile or s3File is required");
		}
		if (s3File != null) {
			s3File.validate();
		}
	}
	
	private void createFunction(AWSLambda lambda) throws IOException {
		// to enable conventionMappings feature
		File zipFile = getZipFile();
		S3File s3File = getS3File();
		
		FunctionCode functionCode;
		if (zipFile != null) {
			try (RandomAccessFile raf = new RandomAccessFile(getZipFile(), "r");
					FileChannel channel = raf.getChannel()) {
				MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
				buffer.load();
				functionCode = new FunctionCode().withZipFile(buffer);
			}
		} else {
			// assume s3File is not null
			functionCode = new FunctionCode()
				.withS3Bucket(s3File.getBucketName())
				.withS3Key(s3File.getKey())
				.withS3ObjectVersion(s3File.getObjectVersion());
		}
		CreateFunctionRequest request = new CreateFunctionRequest()
			.withFunctionName(getFunctionName())
			.withRuntime(getRuntime())
			.withRole(getRole())
			.withHandler(getHandler())
			.withDescription(getFunctionDescription())
			.withTimeout(getLambdaTimeout())
			.withMemorySize(getMemorySize())
			.withPublish(getPublish())
			.withVpcConfig(getVpcConfig())
			.withEnvironment(new Environment().withVariables(getEnvironment()))
			.withTags(getTags())
			.withLayers(getLayers())
			.withCode(functionCode);
		createFunctionResult = lambda.createFunction(request);
		getLogger().info("Create Lambda function requested: {}", createFunctionResult.getFunctionArn());
		
		if (getAlias() != null) {
			createOrUpdateAlias(lambda, createFunctionResult.getVersion());
		}
	}
	
	private void updateFunctionCode(AWSLambda lambda) throws IOException {
		// to enable conventionMappings feature
		File zipFile = getZipFile();
		S3File s3File = getS3File();
		
		UpdateFunctionCodeRequest request = new UpdateFunctionCodeRequest()
			.withFunctionName(getFunctionName());
		if (zipFile != null) {
			try (RandomAccessFile raf = new RandomAccessFile(getZipFile(), "r");
					FileChannel channel = raf.getChannel()) {
				MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
				buffer.load();
				request = request.withZipFile(buffer);
			}
		} else {
			// assume s3File is not null
			request = request
				.withS3Bucket(s3File.getBucketName())
				.withS3Key(s3File.getKey())
				.withS3ObjectVersion(s3File.getObjectVersion());
		}
		
		if (getPublish() != null) {
			request.withPublish(getPublish());
		}
		
		UpdateFunctionCodeResult updateFunctionCode = lambda.updateFunctionCode(request);
		getLogger().info("Update Lambda function requested: {}", updateFunctionCode.getFunctionArn());
		
		if (getAlias() != null) {
			createOrUpdateAlias(lambda, updateFunctionCode.getVersion());
		}
	}
	
	private void updateFunctionConfiguration(AWSLambda lambda, FunctionConfiguration config) {
		String updateFunctionName = getOrElse(() -> getFunctionName(), () -> config.getFunctionName());
		
		String updateRole = getOrElse(() -> getRole(), () -> config.getRole());
		
		Runtime updateRuntime = getOrElse(() -> getRuntime(), () -> Runtime.fromValue(config.getRuntime()));
		
		String updateHandler = getOrElse(() -> getHandler(), () -> config.getHandler());
		
		String updateDescription = getOrElse(() -> getFunctionDescription(), () -> config.getDescription());
		
		Integer updateTimeout = getOrElse(() -> getLambdaTimeout(), () -> config.getTimeout());
		
		Integer updateMemorySize = getOrElse(() -> getMemorySize(), () -> config.getMemorySize());
		
		Map<String, String> environmentVariables = new HashMap<>();
		if (config.getEnvironment() != null) {
			environmentVariables.putAll(config.getEnvironment().getVariables());
		}
		if (getEnvironment() != null) {
			environmentVariables.putAll(getEnvironment());
		}
		
		UpdateFunctionConfigurationRequest request = new UpdateFunctionConfigurationRequest()
			.withFunctionName(updateFunctionName)
			.withRole(updateRole)
			.withRuntime(updateRuntime)
			.withHandler(updateHandler)
			.withDescription(updateDescription)
			.withTimeout(updateTimeout)
			.withVpcConfig(getVpcConfig())
			.withEnvironment(new Environment().withVariables(environmentVariables))
			.withLayers(getLayers())
			.withMemorySize(updateMemorySize);
		
		UpdateFunctionConfigurationResult updateFunctionConfiguration = lambda.updateFunctionConfiguration(request);
		getLogger().info("Update Lambda function configuration requested: {}",
				updateFunctionConfiguration.getFunctionArn());
		
		tagFunction(lambda, config);
	}
	
	private <T> T getOrElse(Supplier<T> primary, Supplier<T> secondary) {
		T primaryValue = primary.get();
		if (primaryValue == null) {
			return secondary.get();
		}
		return primaryValue;
	}
	
	private void createOrUpdateAlias(AWSLambda lambda, String functionVersion) {
		getLogger().info("Create or Update alias {} for {}", getAlias(), functionVersion);
		try {
			updateAlias(lambda, functionVersion);
		} catch (ResourceNotFoundException e) {
			createAlias(lambda, functionVersion);
		}
	}
	
	private void updateAlias(AWSLambda lambda, String functionVersion) {
		UpdateAliasRequest updateAliasRequest = new UpdateAliasRequest()
			.withFunctionName(getFunctionName())
			.withFunctionVersion(functionVersion)
			.withName(getAlias());
		
		UpdateAliasResult updateAliasResult = lambda.updateAlias(updateAliasRequest);
		
		getLogger().info("Update Lambda alias requested: {}",
				updateAliasResult.getAliasArn());
	}
	
	private void createAlias(AWSLambda lambda, String functionVersion) {
		CreateAliasRequest createAliasRequest = new CreateAliasRequest()
			.withFunctionName(getFunctionName())
			.withFunctionVersion(functionVersion)
			.withName(getAlias());
		
		CreateAliasResult createAliasResult = lambda.createAlias(createAliasRequest);
		
		getLogger().info("Create Lambda alias requested: {}",
				createAliasResult.getAliasArn());
	}
	
	private VpcConfig getVpcConfig() {
		if (getVpc() != null) {
			return getVpc().toVpcConfig();
		}
		return null;
	}
	
	private void tagFunction(AWSLambda lambda, FunctionConfiguration config) {
		if (getTags() != null) {
			ListTagsRequest listTagsRequest = new ListTagsRequest()
				.withResource(config.getFunctionArn());
			
			ListTagsResult listTagsResult = lambda.listTags(listTagsRequest);
			
			if (!listTagsResult.getTags().isEmpty()) {
				MapDifference<String, String> tagDifferences =
						Maps.difference(listTagsResult.getTags(), getTags());
				
				UntagResourceRequest untagResourceRequest = new UntagResourceRequest()
					.withResource(config.getFunctionArn())
					.withTagKeys(tagDifferences.entriesOnlyOnLeft().keySet());
				lambda.untagResource(untagResourceRequest);
			}
			
			if (!getTags().isEmpty()) {
				TagResourceRequest tagResourceRequest = new TagResourceRequest()
					.withTags(getTags())
					.withResource(config.getFunctionArn());
				
				lambda.tagResource(tagResourceRequest);
				getLogger().info("Update Lambda function tags requested: {}", config.getFunctionArn());
			}
		}
	}
}
