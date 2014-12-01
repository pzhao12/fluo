/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.cluster;

import java.io.File;

import io.fluo.cluster.util.MainOptions;

import com.beust.jcommander.JCommander;
import io.fluo.api.client.FluoAdmin;
import io.fluo.api.client.FluoAdmin.AlreadyInitializedException;
import io.fluo.api.client.FluoFactory;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.cluster.util.LogbackUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * Initializes Fluo using properties in configuration files
 */
public class FluoInitializeMain {
  
  private static final Logger log = LoggerFactory.getLogger(FluoInitializeMain.class);

  public static void main(String[] args) throws Exception {

    MainOptions options = new MainOptions();
    JCommander jcommand = new JCommander(options, args);

    if (options.help) {
      jcommand.usage();
      System.exit(-1);
    }
    options.validateConfig();

    LogbackUtil.init("init", options.getConfigDir(), options.getLogOutput(), false);
    
    FluoConfiguration config = new FluoConfiguration(new File(options.getFluoProps()));
    if (!config.hasRequiredAdminProps()) {
      log.error("Initialization failed!  Required properties are not set in "+options.getFluoProps());
      System.exit(-1);
    }
    
    log.info("Initializing Fluo instance using " + options.getFluoProps());

    FluoAdmin admin = FluoFactory.newAdmin(config);
    try {
      admin.initialize();
    } catch (AlreadyInitializedException aie) {
      admin.updateSharedConfig();
    }
    
    log.info("Initialization is complete.");
  }
}