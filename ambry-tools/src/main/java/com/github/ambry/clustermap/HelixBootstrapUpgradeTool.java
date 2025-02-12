/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.clustermap;

import com.github.ambry.config.ClusterMapConfig;
import com.github.ambry.tools.util.ToolUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;

import static com.github.ambry.clustermap.HelixBootstrapUpgradeUtil.*;
import static com.github.ambry.clustermap.HelixBootstrapUpgradeUtil.HelixAdminOperation.*;


/**
 * This tool takes the hardware layout, partition layout and the Zk hosts information json files as input,
 * and updates the ZK hosts with the contents of the layout files. It adds all partitions and hosts that have not
 * previously been added (so, initially this will bootstrap the cluster by adding new nodes and partitions).
 *
 * The existing hardware and partition layout json files will be read in as is.
 *
 * The ZkLayoutPath argument containing the Zk hosts information in each datacenter should be a json of the
 * following example form:
 *
 * {
 *   "zkInfo" : [
 *     {
 *       "datacenter":"dc1",
 *       "id": "1",
 *       "zkConnectStr":"abc.example.com:2199",
 *     },
 *     {
 *       "datacenter":"dc2",
 *       "id" : "2",
 *       "zkConnectStr":"def.example.com:2300",
 *     }
 *   ]
 * }
 *
 * This tool should be run from an admin node that has access to the nodes in the hardware layout. The access is
 * required because the static {@link StaticClusterManager} that we use to parse the static layout files validates
 * these nodes.
 *
 * The tool does the following:
 * 1. Bootstraps a static cluster map by adding nodes and partitions to Helix.
 * 2. Upgrades the information with changes in the static clustermap that involve new nodes and new partitions.
 *    To avoid over-complicating things, it assumes that the existing partition assignment does not change during an
 *    upgrade. Newly added partitions can be distributed in any way (new partitions can have replicas even in
 *    previously added nodes).
 * 3. Upgrades will also update the partition states if required (READ_WRITE to SEALED or vice versa) for existing
 *    partitions.
 *
 */
public class HelixBootstrapUpgradeTool {
  /**
   * @param args takes in three mandatory arguments: the hardware layout path, the partition layout path and the zk
   *             layout path.
   *             The Zk layout has to be of the following form:
   *             {
   *               "zkInfo" : [
   *                 {
   *                   "datacenter":"dc1",
   *                   "id": "1",
   *                   "zkConnectStr":"abc.example.com:2199",
   *                 },
   *                 {
   *                   "datacenter":"dc2",
   *                    "id": "2",
   *                   "zkConnectStr":"def.example.com:2300",
   *                 }
   *               ]
   *             }
   *
   *             Also takes in an optional argument that specifies the local datacenter name, so that can be used as
   *             the "reference" datacenter. If none provided, the tool simply chooses one of the datacenters in the
   *             layout as the reference datacenter.
   */
  public static void main(String[] args) throws Exception {
    OptionParser parser = new OptionParser();

    OptionSpec<Void> dropClusterOpt = parser.accepts("dropCluster",
        "Drops the given Ambry cluster from Helix. Use this option with care. If present, must be accompanied with and "
            + "only with the clusterName argument");

    OptionSpec<Void> forceRemove = parser.accepts("forceRemove",
        "Specifies that any instances or partitions absent in the json files be removed from Helix. Use this with care");

    OptionSpec<Void> addStateModel = parser.accepts("addStateModel",
        "Attempt to add new state model to Helix StateModelDefs if it doesn't exist. This option will not touch instanceConfig");

    ArgumentAcceptingOptionSpec<String> hardwareLayoutPathOpt =
        parser.accepts("hardwareLayoutPath", "The path to the hardware layout json file")
            .requiredUnless(dropClusterOpt)
            .withRequiredArg()
            .describedAs("hardware_layout_path")
            .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> partitionLayoutPathOpt =
        parser.accepts("partitionLayoutPath", "The path to the partition layout json file")
            .requiredUnless(dropClusterOpt)
            .withRequiredArg()
            .describedAs("partition_layout_path")
            .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> zkLayoutPathOpt = parser.accepts("zkLayoutPath",
        "The path to the json file containing zookeeper connect info. This should be of the following form: \n{\n"
            + "  \"zkInfo\" : [\n" + "     {\n" + "       \"datacenter\":\"dc1\",\n"
            + "       \"zkConnectStr\":\"abc.example.com:2199\",\n" + "     },\n" + "     {\n"
            + "       \"datacenter\":\"dc2\",\n" + "       \"zkConnectStr\":\"def.example.com:2300\",\n" + "     },\n"
            + "     {\n" + "       \"datacenter\":\"dc3\",\n" + "       \"zkConnectStr\":\"ghi.example.com:2400\",\n"
            + "     }\n" + "  ]\n" + "}")
        .requiredUnless(dropClusterOpt)
        .withRequiredArg()
        .describedAs("zk_connect_info_path")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> clusterNamePrefixOpt =
        parser.accepts("clusterNamePrefix", "The prefix for the cluster in Helix to bootstrap or upgrade")
            .withRequiredArg()
            .describedAs("cluster_name_prefix")
            .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> clusterNameOpt =
        parser.accepts("clusterName", "The cluster in Helix to drop. This should accompany the dropCluster option")
            .requiredIf(dropClusterOpt)
            .withRequiredArg()
            .describedAs("cluster_name")
            .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> dcsNameOpt = parser.accepts("dcs",
        "The comma-separated datacenters (colos) to update. Use '--dcs all' if updates to every datacenter is intended")
        .withRequiredArg()
        .describedAs("datacenters")
        .required()
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> maxPartitionsInOneResourceOpt = parser.accepts("maxPartitionsInOneResource",
        "(Optional argument) The maximum number of partitions that should be grouped under a Helix resource. "
            + "If the resources are reconstructed to be FULL_AUTO compatible, then this option would be ignored")
        .withRequiredArg()
        .describedAs("max_partitions_in_one_resource")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> stateModelDefinitionOpt = parser.accepts("stateModelDef",
        "(Optional argument) The state model definition that should be created in cluster if doesn't exist")
        .withRequiredArg()
        .describedAs("state_model_definition")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> adminConfigsOpt = parser.accepts("adminConfigs",
        "(Optional argument) Upload cluster admin configs to HelixPropertyStore based on json files. Currently, "
            + "the tool supports (1) partition override config, (2) replica addition config. The config names are "
            + "comma-separated and case-sensitive, for example: '--adminConfigs PartitionOverride,ReplicaAddition'. "
            + "This option will not modify instanceConfig and IdealState")
        .withRequiredArg()
        .describedAs("admin_configs")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> adminOperationOpt = parser.accepts("adminOperation",
        "(Optional argument) Perform admin operations to manage resources in cluster. For example: "
            + " '--adminOperation UpdateIdealState' # Update IdealState based on static clustermap. This won't change InstanceConfig"
            + " '--adminOperation DisablePartition' # Disable partition on certain node. Usually used as first step to decommission replica(s)"
            + " '--adminOperation EnablePartition' # Enable partition on certain node (if partition is previously disabled)"
            + " '--adminOperation ResetPartition' # Reset partition on certain node (if partition is previously in error state)"
            + " '--adminOperation ListSealedPartition' # List all sealed partitions in Helix cluster (aggregated across all datacenters)"
            + " '--adminOperation ValidateCluster' # Validates the information in static clustermap is consistent with the information in Helix"
            + " '--adminOperation MigrateToPropertyStore' # Migrate custom instance config properties to DataNodeConfigs in the property store"
            + " '--adminOperation MigrateToFullAuto' # Migrate resources to Full Auto"
            + " '--adminOperation BootstrapCluster' # (Default operation if not specified) Bootstrap cluster based on static clustermap")
        .withRequiredArg()
        .describedAs("admin_operation")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> hostnameOpt = parser.accepts("hostname",
        "(Optional argument and is always accompanied with partition control operations, i.e EnablePartition, "
            + "DisablePartition) The host on which admin operation should be performed")
        .withRequiredArg()
        .describedAs("hostname")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> portOpt = parser.accepts("port",
        "(Optional argument and is always accompanied with partition control operations, i.e EnablePartition, "
            + "DisablePartition) The port number associated with the host on which admin operation should be performed."
            + "If not specified, the tool attempts to find host from static clustermap by searching hostname.")
        .withRequiredArg()
        .describedAs("port")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> partitionIdOpt = parser.accepts("partition",
        "(Optional argument and is always accompanied with partition control operations, i.e EnablePartition, "
            + "DisablePartition) The partition on which admin operation should be performed")
        .withRequiredArg()
        .describedAs("partition")
        .ofType(String.class);

    OptionSpecBuilder dryRun =
        parser.accepts("dryRun", "(Optional argument) Dry run, do not modify the cluster map in Helix.");

    OptionSpecBuilder enableValidatingClusterManager = parser.accepts("enableVCM",
        "(Optional argument) whether to enable validating cluster manager(VCM) in Helix bootstrap tool.");

    ArgumentAcceptingOptionSpec<String> adminConfigFilePathOpt = parser.accepts("adminConfigFilePath",
        "The path to a static admin config file. For example, it can be a file that holds a list of partitions"
            + "(comma separated) that should be overridden to ReadOnly")
        .withRequiredArg()
        .describedAs("admin_config_file_path")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> dataNodeConfigSourceOpt = parser.accepts("dataNodeConfigSource",
        "(Optional argument) The type of data node config source (default is PROPERTY_STORE). "
            + "See DataNodeConfigSourceType enum for more details.")
        .withRequiredArg()
        .describedAs("data_node_config_source")
        .ofType(String.class);

    OptionSpecBuilder overrideReplicaStatus = parser.accepts("overrideReplicaStatus",
        "(Optional argument) whether to override replica status lists (i.e. sealed/stopped/disabled lists) in instance(datanode) config");

    ArgumentAcceptingOptionSpec<Integer> maxInstancesInOneResourceForFullAutoOpt =
        parser.accepts("maxInstancesInOneResourceForFullAuto",
            "Maximum number of instance in a resource when the resources are constructed as FULL_AUTO compatible "
                + "or the cluster is empty without any resources.\n"
                + "This is only required when bootstrapping a cluster or update ideal state, or validating a cluster\n\n"
                + "When the cluster is empty, if you provide 0 to this option, this tool would create resources in the old way, not full auto compatible way")
            .withRequiredArg()
            .describedAs("max_instances_in_one_resource_for_full_auto")
            .ofType(Integer.class);

    ArgumentAcceptingOptionSpec<String> resourcesNameOpt = parser.accepts("resources",
        "The comma-separated resources to migrate to Full Auto. Use '--resources all' to migrate all resources")
        .withRequiredArg()
        .describedAs("resources")
        .ofType(String.class);

    ArgumentAcceptingOptionSpec<String> wagedConfigFilePathOpt = parser.accepts("wagedConfigFilePathOpt",
        "The path to the waged config file path")
        .withRequiredArg()
        .describedAs("waged_config_file_path")
        .ofType(String.class);

    OptionSet options = parser.parse(args);
    String hardwareLayoutPath = options.valueOf(hardwareLayoutPathOpt);
    String partitionLayoutPath = options.valueOf(partitionLayoutPathOpt);
    String adminConfigFilePath = options.valueOf(adminConfigFilePathOpt);
    String zkLayoutPath = options.valueOf(zkLayoutPathOpt);
    String clusterNamePrefix = options.valueOf(clusterNamePrefixOpt);
    String clusterName = options.valueOf(clusterNameOpt);
    String dcs = options.valueOf(dcsNameOpt);
    String adminConfigStr = options.valueOf(adminConfigsOpt);
    String adminOpStr = options.valueOf(adminOperationOpt);
    String hostname = options.valueOf(hostnameOpt);
    String partitionName = options.valueOf(partitionIdOpt);
    String portStr = options.valueOf(portOpt);
    Integer maxInstancesInOneResourceForFullAuto = options.valueOf(maxInstancesInOneResourceForFullAutoOpt);
    String wagedConfigFilePath = options.valueOf(wagedConfigFilePathOpt);
    int maxPartitionsInOneResource =
        options.valueOf(maxPartitionsInOneResourceOpt) == null ? DEFAULT_MAX_PARTITIONS_PER_RESOURCE
            : Integer.parseInt(options.valueOf(maxPartitionsInOneResourceOpt));
    String stateModelDef = options.valueOf(stateModelDefinitionOpt) == null ? ClusterMapConfig.DEFAULT_STATE_MODEL_DEF
        : options.valueOf(stateModelDefinitionOpt);
    DataNodeConfigSourceType dataNodeConfigSourceType =
        options.valueOf(dataNodeConfigSourceOpt) == null ? DataNodeConfigSourceType.PROPERTY_STORE
            : DataNodeConfigSourceType.valueOf(options.valueOf(dataNodeConfigSourceOpt));
    String resources = options.valueOf(resourcesNameOpt) == null ? "all" : options.valueOf(resourcesNameOpt);
    ArrayList<OptionSpec> listOpt = new ArrayList<>();
    listOpt.add(hardwareLayoutPathOpt);
    listOpt.add(partitionLayoutPathOpt);
    listOpt.add(zkLayoutPathOpt);
    listOpt.add(clusterNamePrefixOpt);
    listOpt.add(dcsNameOpt);
    if (options.has(dropClusterOpt)) {
      List<OptionSpec<?>> expectedOpts = Arrays.asList(dropClusterOpt, clusterNameOpt, zkLayoutPathOpt, dcsNameOpt);
      ToolUtils.ensureExactOrExit(expectedOpts, options.specs(), parser);
      HelixBootstrapUpgradeUtil.dropCluster(zkLayoutPath, clusterName, dcs, new HelixAdminFactory());
    } else if (adminConfigStr != null) {
      listOpt.add(adminConfigsOpt);
      ToolUtils.ensureOrExit(listOpt, options, parser);
      String[] adminTypes = adminConfigStr.replaceAll("\\p{Space}", "").split(",");
      HelixBootstrapUpgradeUtil.uploadOrDeleteAdminConfigs(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath,
          clusterNamePrefix, dcs, options.has(forceRemove), adminTypes, adminConfigFilePath);
    } else if (options.has(addStateModel)) {
      listOpt.add(stateModelDefinitionOpt);
      ToolUtils.ensureOrExit(listOpt, options, parser);
      HelixBootstrapUpgradeUtil.addStateModelDef(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath,
          clusterNamePrefix, dcs, stateModelDef);
    } else {
      // The default operation is BootstrapCluster (if not specified)
      HelixAdminOperation operation = adminOpStr == null ? BootstrapCluster : HelixAdminOperation.valueOf(adminOpStr);
      ToolUtils.ensureOrExit(listOpt, options, parser);
      Integer portNum = portStr == null ? null : Integer.parseInt(portStr);
      switch (operation) {
        case ValidateCluster:
          listOpt.add(maxInstancesInOneResourceForFullAutoOpt);
          ToolUtils.ensureOrExit(listOpt, options, parser);
          HelixBootstrapUpgradeUtil.validate(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath, clusterNamePrefix,
              dcs, stateModelDef, dataNodeConfigSourceType, maxInstancesInOneResourceForFullAuto.intValue());
          break;
        case ListSealedPartition:
          HelixBootstrapUpgradeUtil.listSealedPartition(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath,
              clusterNamePrefix, dcs, dataNodeConfigSourceType);
          break;
        case MigrateToPropertyStore:
          HelixBootstrapUpgradeUtil.migrateToPropertyStore(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath,
              clusterNamePrefix, dcs);
          break;
        case MigrateToFullAuto:
          listOpt.add(wagedConfigFilePathOpt);
          ToolUtils.ensureOrExit(listOpt, options, parser);
          HelixBootstrapUpgradeUtil.migrateToFullAuto(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath,
              clusterNamePrefix, dcs, resources, options.has(dryRun), wagedConfigFilePath);
          break;
        case ResetPartition:
        case EnablePartition:
          HelixBootstrapUpgradeUtil.controlPartitionState(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath,
              clusterNamePrefix, dcs, hostname, portNum, operation, partitionName);
          break;
        case DisablePartition:
          // if user specifies hostname and partition name, tool directly disables this partition on given node without
          // parsing partitions from clustermap.
          if (hostname != null && partitionName != null) {
            HelixBootstrapUpgradeUtil.controlPartitionState(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath,
                clusterNamePrefix, dcs, hostname, portNum, operation, partitionName);
            break;
          }
          // if user would like to disable multiple partitions concurrently, then hostname and partition name can be empty,
          // the tool goes to default branch and extracts removed replicas from static clustermap to disable them
          // automatically.
        default:
          listOpt.add(maxInstancesInOneResourceForFullAutoOpt);
          ToolUtils.ensureOrExit(listOpt, options, parser);
          HelixBootstrapUpgradeUtil.bootstrapOrUpgrade(hardwareLayoutPath, partitionLayoutPath, zkLayoutPath,
              clusterNamePrefix, dcs, maxPartitionsInOneResource, options.has(dryRun), options.has(forceRemove), null,
              options.has(enableValidatingClusterManager), stateModelDef, operation, dataNodeConfigSourceType,
              options.has(overrideReplicaStatus), maxInstancesInOneResourceForFullAuto.intValue());
      }
    }
    System.out.println("======== HelixBootstrapUpgradeTool completed successfully! ========");
    System.out.println("( If program doesn't exit, please use Ctrl-c to terminate. )");
    System.exit(0);
  }
}

