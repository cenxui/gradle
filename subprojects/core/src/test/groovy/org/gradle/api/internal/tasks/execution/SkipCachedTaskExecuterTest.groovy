/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.internal.tasks.execution

import org.gradle.StartParameter
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.TaskOutputsInternal
import org.gradle.api.internal.changedetection.TaskArtifactState
import org.gradle.api.internal.tasks.TaskExecuter
import org.gradle.api.internal.tasks.TaskExecutionContext
import org.gradle.api.internal.tasks.TaskStateInternal
import org.gradle.api.internal.tasks.cache.TaskCacheKey
import org.gradle.api.internal.tasks.cache.TaskOutputCache
import org.gradle.api.internal.tasks.cache.TaskOutputCacheFactory
import org.gradle.api.internal.tasks.cache.TaskOutputPacker
import org.gradle.api.internal.tasks.cache.TaskOutputReader
import org.gradle.api.internal.tasks.cache.TaskOutputWriter
import org.gradle.api.internal.tasks.cache.config.TaskCachingInternal
import spock.lang.Specification

public class SkipCachedTaskExecuterTest extends Specification {
    def delegate = Mock(TaskExecuter)
    def task = Mock(TaskInternal)
    def project = Mock(Project)
    def projectDir = Mock(File)
    def outputs = Mock(TaskOutputsInternal)
    def taskState = Mock(TaskStateInternal)
    def taskContext = Mock(TaskExecutionContext)
    def taskArtifactState = Mock(TaskArtifactState)
    def taskOutputCache = Mock(TaskOutputCache)
    def taskOutputCacheFactory = Mock(TaskOutputCacheFactory)
    def taskCaching = Mock(TaskCachingInternal)
    def taskOutputPacker = Mock(TaskOutputPacker)
    def startParameter = Mock(StartParameter)
    def cacheKey = Mock(TaskCacheKey)

    def executer = new SkipCachedTaskExecuter(taskCaching, taskOutputPacker, startParameter, delegate)

    def "skip task when cached results exist"() {
        def cachedResult = Mock(TaskOutputReader)

        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> true

        1 * taskContext.getTaskArtifactState() >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey

        1 * taskCaching.getCacheFactory() >> taskOutputCacheFactory
        1 * taskOutputCacheFactory.createCache(_) >> taskOutputCache
        1 * taskOutputCache.getDescription() >> "test"
        1 * taskOutputCache.get(cacheKey) >> cachedResult
        1 * taskOutputPacker.unpack(outputs, cachedResult)
        1 * taskState.upToDate("FROM-CACHE")
        0 * _
    }

    def "executes task when no cached result is available"() {
        def cachedResult = Mock(TaskOutputWriter)

        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> true

        1 * taskContext.getTaskArtifactState() >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey

        1 * taskCaching.getCacheFactory() >> taskOutputCacheFactory
        1 * taskOutputCacheFactory.createCache(_) >> taskOutputCache
        1 * taskOutputCache.getDescription() >> "test"
        1 * taskOutputCache.get(cacheKey) >> null

        then:
        1 * delegate.execute(task, taskState, taskContext)
        1 * taskState.getFailure() >> null

        then:
        1 * taskOutputPacker.createWriter(outputs) >> cachedResult
        1 * taskOutputCache.put(cacheKey, cachedResult)
        0 * _
    }

    def "does not cache results when executed task fails"() {
        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> true

        1 * taskContext.getTaskArtifactState() >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey

        1 * taskCaching.getCacheFactory() >> taskOutputCacheFactory
        1 * taskOutputCacheFactory.createCache(_) >> taskOutputCache
        1 * taskOutputCache.getDescription() >> "test"
        1 * taskOutputCache.get(cacheKey) >> null

        then:
        1 * delegate.execute(task, taskState, taskContext)
        1 * taskState.getFailure() >> new RuntimeException()
        0 * _
    }

    def "executes task and does not cache results when cacheIf is false"() {
        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> false

        then:
        1 * delegate.execute(task, taskState, taskContext)
        0 * _
    }

    def "executes task and does not cache results when task is not allowed to use cache"() {
        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> false

        then:
        1 * delegate.execute(task, taskState, taskContext)
        0 * _
    }

    def "fails when cacheIf() clause cannot be evaluated"() {
        when:
        executer.execute(task, taskState, taskContext)

        then:
        def ex = thrown GradleException
        ex.message == "Could not evaluate TaskOutputs.cacheIf for ${task}." as String
        ex.cause instanceof RuntimeException
        ex.cause.message == "Bad cacheIf() clause"

        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> { throw new RuntimeException("Bad cacheIf() clause") }
    }

    def "fails if cache key cannot be calculated"() {
        when:
        executer.execute(task, taskState, taskContext)

        then:
        def ex = thrown GradleException
        ex.message == "Could not build cache key for ${task}." as String
        ex.cause instanceof RuntimeException
        ex.cause.message == "Bad cache key"

        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> true

        1 * taskContext.getTaskArtifactState() >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> { throw new RuntimeException("Bad cache key") }
    }

    def "falls back to executing task when cache backend throws error while finding result"() {
        def cachedResult = Mock(TaskOutputWriter)
        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> true

        1 * taskContext.getTaskArtifactState() >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey

        1 * taskCaching.getCacheFactory() >> taskOutputCacheFactory
        1 * taskOutputCacheFactory.createCache(_) >> taskOutputCache
        1 * taskOutputCache.getDescription() >> "test"
        1 * taskOutputCache.get(cacheKey) >> { throw new RuntimeException("Bad cache") }

        then:
        1 * delegate.execute(task, taskState, taskContext)
        1 * taskState.getFailure() >> null

        then:
        1 * taskOutputPacker.createWriter(outputs) >> cachedResult
        1 * taskOutputCache.put(cacheKey, cachedResult)
        0 * _
    }

    def "falls back to executing task when cache backend throws error while loading result"() {
        def foundResult = Mock(TaskOutputReader)
        def cachedResult = Mock(TaskOutputWriter)

        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> true

        1 * taskContext.getTaskArtifactState() >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey

        1 * taskCaching.getCacheFactory() >> taskOutputCacheFactory
        1 * taskOutputCacheFactory.createCache(_) >> taskOutputCache
        1 * taskOutputCache.getDescription() >> "test"
        1 * taskOutputCache.get(cacheKey) >> foundResult
        1 * taskOutputPacker.unpack(outputs, foundResult) >> { throw new RuntimeException("Bad result") }

        then:
        1 * delegate.execute(task, taskState, taskContext)
        1 * taskState.getFailure() >> null

        then:
        1 * taskOutputPacker.createWriter(outputs) >> cachedResult
        1 * taskOutputCache.put(cacheKey, cachedResult)
        0 * _
    }

    def "ignores error when storing cached result"() {
        def cachedResult = Mock(TaskOutputWriter)

        when:
        executer.execute(task, taskState, taskContext)

        then:
        1 * task.getOutputs() >> outputs
        1 * outputs.isCacheAllowed() >> true
        1 * outputs.isCacheEnabled() >> true

        1 * taskContext.getTaskArtifactState() >> taskArtifactState
        1 * taskArtifactState.calculateCacheKey() >> cacheKey

        1 * taskCaching.getCacheFactory() >> taskOutputCacheFactory
        1 * taskOutputCacheFactory.createCache(_) >> taskOutputCache
        1 * taskOutputCache.getDescription() >> "test"
        1 * taskOutputCache.get(cacheKey) >> null

        then:
        1 * delegate.execute(task, taskState, taskContext)
        1 * taskState.getFailure() >> null

        then:
        1 * taskOutputPacker.createWriter(outputs) >> cachedResult
        1 * taskOutputCache.put(cacheKey, cachedResult) >> { throw new RuntimeException("Bad result") }
        0 * _
    }
}
