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
package jp.classmethod.aws.reboot.gradle.ecr;

import lombok.Getter;
import lombok.Setter;

import org.gradle.api.Project;
import org.gradle.api.tasks.Input;

import com.amazonaws.services.ecr.AmazonECRClient;

import jp.classmethod.aws.reboot.gradle.common.BaseRegionAwarePluginExtension;

public class AmazonECRPluginExtension extends BaseRegionAwarePluginExtension<AmazonECRClient> {
	
	public static final String NAME = "ecr";
	
	@Getter(onMethod = @__(@Input))
	@Setter
	private String repositoryName;
	
	
	public AmazonECRPluginExtension(Project project) {
		super(project, AmazonECRClient.class);
	}
}
