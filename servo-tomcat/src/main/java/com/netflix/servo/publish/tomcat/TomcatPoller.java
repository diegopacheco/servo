/*
 * Copyright 2015 Netflix, Inc.
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
package com.netflix.servo.publish.tomcat;

import com.netflix.servo.Metric;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.publish.BaseMetricPoller;
import com.netflix.servo.tag.BasicTagList;
import com.netflix.servo.tag.Tag;
import com.netflix.servo.tag.TagList;
import com.netflix.servo.tag.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Fetch Tomcat metrics from JMX.
 */
public class TomcatPoller extends BaseMetricPoller {
    private static final List<Metric> EMPTY_LIST = Collections.emptyList();
    private static final Logger LOGGER = LoggerFactory.getLogger(TomcatPoller.class);

    /**
     * Create a new TomcatPoller.
     */
    public TomcatPoller() {
    }

    private static Tag catalina = Tags.newTag("source", "Catalina");

    private static Metric toMetric(long t, ObjectName name, Attribute attribute, Tag dsType) {
        Tag pool = Tags.newTag("pool", name.getKeyProperty("name"));
        Tag clazz = Tags.newTag("class", name.getKeyProperty("type"));
        TagList list = BasicTagList.of(catalina, pool, clazz, dsType);
        return new Metric(attribute.getName(), list, t, attribute.getValue());
    }

    private static Metric toGauge(long t, ObjectName name, Attribute attribute) {
        return toMetric(t, name, attribute, DataSourceType.GAUGE);
    }

    private static Metric toCounter(long t, ObjectName name, Attribute attribute) {
        return toMetric(t, name, attribute, DataSourceType.COUNTER);
    }

    private static final String[] THREAD_POOL_ATTRS = new String[]{
            "maxThreads",
            "currentThreadCount",
            "currentThreadsBusy",
            "backlog"
    };

    private static final String[] GLOBAL_REQ_ATTRS = new String[]{
            "requestCount",
            "errorCount",
            "bytesSent",
            "processingTime",
            "maxTime"
    };

    private static final String[] EXECUTOR_ATTRS = new String[]{
            "poolSize",
            "activeCount"
    };

    private static void fetchRequestProcessorMetrics(long now, MBeanServer mbs, List<Metric> out)
            throws JMException {
        Set<ObjectName> names = mbs.queryNames(new ObjectName("Catalina:type=GlobalRequestProcessor,*"), null);
        if (names == null) {
            return;
        }

        for (ObjectName name : names) {
            AttributeList list = mbs.getAttributes(name, GLOBAL_REQ_ATTRS);
            for (Attribute a : list.asList()) {
                if (!a.getName().equals("maxTime")) {
                    out.add(toCounter(now, name, a));
                } else {
                    // the only gauge here is maxTime
                    out.add(toGauge(now, name, a));
                }
            }
        }
        LOGGER.info("GlobalReqProc={}", out);
    }

    private static void fetchThreadPoolMetrics(long now, MBeanServer mbs, List<Metric> out)
            throws JMException {
        Set<ObjectName> names = mbs.queryNames(new ObjectName("Catalina:type=ThreadPool,*"), null);
        if (names == null) {
            return;
        }

        for (ObjectName name : names) {
            AttributeList list = mbs.getAttributes(name, THREAD_POOL_ATTRS);
            for (Attribute a : list.asList()) {
                out.add(toGauge(now, name, a));
            }
        }
    }

    private static void fetchExecutorMetrics(long now, MBeanServer mbs, List<Metric> out)
            throws JMException {
        Set<ObjectName> names = mbs.queryNames(new ObjectName("Catalina:type=Executor,*"), null);
        if (names == null) {
            return;
        }

        for (ObjectName name : names) {
            AttributeList list = mbs.getAttributes(name, EXECUTOR_ATTRS);
            for (Attribute a : list.asList()) {
                out.add(toGauge(now, name, a));
            }
        }
    }

    List<Metric> pollImpl(long timestamp) {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            List<Metric> metrics = new ArrayList<Metric>();
            fetchThreadPoolMetrics(timestamp, mbs, metrics);
            fetchRequestProcessorMetrics(timestamp, mbs, metrics);
            fetchExecutorMetrics(timestamp, mbs, metrics);
            LOGGER.debug("Generating {}", metrics);
            return metrics;
        } catch (JMException e) {
            logger.error("Could not get Tomcat JMX metrics", e);
            return EMPTY_LIST;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Metric> pollImpl(boolean reset) {
        return pollImpl(System.currentTimeMillis());
    }
}
