/*
 * Copyright 2011-2015 the original author or authors.
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

package org.springframework.xd.dirt.module;

import static org.springframework.xd.dirt.stream.ParsingContext.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.util.Assert;
import org.springframework.xd.dirt.stream.XDStreamParser;
import org.springframework.xd.dirt.util.PagingUtility;
import org.springframework.xd.module.ModuleDefinition;
import org.springframework.xd.module.ModuleDefinitions;
import org.springframework.xd.module.ModuleDescriptor;
import org.springframework.xd.module.ModuleType;

/**
 * A service that knows how to handle registration of new module definitions, be it through composition or
 * upload of actual 'bytecode'. Handles all bookkeeping (such as existence checking, dependency tracking, <i>etc.</i>)
 * that is common to all registration scenarios.
 *
 * <p>Also adds pagination to {@code find*()} methods of {@code ModuleRegistry} after the fact.</p>
 *
 * @author Eric Bottard
 */
public class ModuleDefinitionService {

	private final WritableModuleRegistry registry;

	private final XDStreamParser parser;

	private final ModuleDependencyRepository dependencyRepository;

	private final PagingUtility<ModuleDefinition> pagingUtility = new PagingUtility<ModuleDefinition>();

	@Autowired
	public ModuleDefinitionService(WritableModuleRegistry registry, XDStreamParser parser, ModuleDependencyRepository dependencyRepository) {
		this.registry = registry;
		this.parser = parser;
		this.dependencyRepository = dependencyRepository;
	}

	public ModuleDefinition findDefinition(String name, ModuleType type) {
		return registry.findDefinition(name, type);
	}

	public Page<ModuleDefinition> findDefinitions(Pageable pageable, String name) {
		List<ModuleDefinition> raw = registry.findDefinitions(name);
		return pagingUtility.getPagedData(pageable, raw);
	}

	public Page<ModuleDefinition> findDefinitions(Pageable pageable, ModuleType type) {
		List<ModuleDefinition> raw = registry.findDefinitions(type);
		return pagingUtility.getPagedData(pageable, raw);
	}

	public Page<ModuleDefinition> findDefinitions(Pageable pageable) {
		List<ModuleDefinition> raw = registry.findDefinitions();
		return pagingUtility.getPagedData(pageable, raw);
	}

	public ModuleDefinition compose(String name, ModuleType typeHint, String dslDefinition, boolean force) {
		// TODO: pass typeHint to parser (XD-2343)
		List<ModuleDescriptor> parseResult = this.parser.parse(name, dslDefinition, module);

		ModuleType type = this.determineType(parseResult);
		assertModuleUpdatability(name, type, force);

		// TODO: XD-2284 need more than ModuleDefinitions (need to capture passed in options, etc)
		List<ModuleDefinition> composedModuleDefinitions = createComposedModuleDefinitions(parseResult);
		ModuleDefinition moduleDefinition = ModuleDefinitions.composed(name, type, dslDefinition, composedModuleDefinitions);

		Assert.isTrue(this.registry.registerNew(moduleDefinition), moduleDefinition + " could not be saved");
		return moduleDefinition;
	}

	public ModuleDefinition upload(String name, ModuleType type, byte[] bytes, boolean force) {
		assertModuleUpdatability(name, type, force);

		ModuleDefinition definition = new UploadedModuleDefinition(name, type, bytes);
		Assert.isTrue(this.registry.registerNew(definition), definition + " could not be saved");
		return definition;
	}

	/**
	 * Throws an exception if either one of the following is true:<ul>
	 *     <li>a module with the given name and type already exists and force is {@code false}</li>
	 *     <li>force if {@true} but the module is in use (by a stream or composed module)</li>
	 * </ul>
	 *
	 * <p>Also, will actually delete the already existing definition in case of an update,
	 * even though it would be overwritten by a registry, to cover the cases where a composed
	 * module is being replaced by a simple uploaded module, and <i>vice versa</i>.</p>
	 *
	 * @param name name of the module we're trying to update/create
	 * @param type type of the module we're trying to update/create
	 * @param force whether to attempt to force update if the module already exists
	 */
	private void assertModuleUpdatability(String name, ModuleType type, boolean force) {
		ModuleDefinition definition = registry.findDefinition(name, type);
		if (definition != null) {
			if (!force) {
				throw new ModuleAlreadyExistsException(name, type);
			} else {
				Set<String> dependents = this.dependencyRepository.find(name, type);
				if (!dependents.isEmpty()) {
					throw new DependencyException("Cannot force update module %2$s:%1$s because it is used by %3$s", name, type, dependents);
				} else {
					// Perform an eager deletion, taking care of module flavor change.
					// Also, this catches cases when we're trying to replace a read-only module
					if (!registry.delete(definition)) {
						throw new ModuleAlreadyExistsException("There is already a module named '%s' with type '%s', and it cannot be updated", name, type);
					}
				}
			}
		}
	}

	public void delete(String name, ModuleType type) {
		ModuleDefinition definition = registry.findDefinition(name, type);
		if (definition == null) {
			throw new NoSuchModuleException(name, type);
		}
		Set<String> dependents = this.dependencyRepository.find(name, type);
		if (!dependents.isEmpty()) {
			throw new DependencyException("Cannot delete module %2$s:%1$s because it is used by %3$s", name, type,
					dependents);
		}

		boolean result = this.registry.delete(definition);
		Assert.isTrue(result, String.format("Could not delete module '%s:%s'", type, name));
	}

	private List<ModuleDefinition> createComposedModuleDefinitions(
			List<ModuleDescriptor> moduleDescriptors) {

		List<ModuleDefinition> moduleDefinitions = new ArrayList<ModuleDefinition>(moduleDescriptors.size());
		for (ModuleDescriptor moduleDescriptor : moduleDescriptors) {
			moduleDefinitions.add(registry.findDefinition(moduleDescriptor.getModuleName(),
					moduleDescriptor.getType()));
		}
		return moduleDefinitions;
	}

	private ModuleType determineType(List<ModuleDescriptor> modules) {
		Assert.isTrue(modules != null && modules.size() > 0, "at least one module required");
		if (modules.size() == 1) {
			return modules.get(0).getType();
		}
		Collections.sort(modules);
		ModuleType firstType = modules.get(0).getType();
		ModuleType lastType = modules.get(modules.size() - 1).getType();
		boolean hasInput = firstType != ModuleType.source;
		boolean hasOutput = lastType != ModuleType.sink;
		if (hasInput && hasOutput) {
			return ModuleType.processor;
		}
		if (hasInput) {
			return ModuleType.sink;
		}
		if (hasOutput) {
			return ModuleType.source;
		}
		throw new IllegalArgumentException("invalid module composition; must expose input and/or output channel");
	}
}
