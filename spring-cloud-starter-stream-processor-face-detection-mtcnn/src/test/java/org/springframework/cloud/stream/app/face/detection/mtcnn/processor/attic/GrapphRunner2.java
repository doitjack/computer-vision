package org.springframework.cloud.stream.app.face.detection.mtcnn.processor.attic;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.os72.protobuf351.ByteString;
import com.github.os72.protobuf351.InvalidProtocolBufferException;
import com.github.os72.protobuf351.util.JsonFormat;
import org.apache.commons.io.IOUtils;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.tensorflow;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.tensorflow.conversion.TensorflowConversion;
import org.nd4j.tensorflow.conversion.graphrunner.GraphRunner;
import org.tensorflow.framework.ConfigProto;
import org.tensorflow.framework.GPUOptions;
import org.tensorflow.framework.NodeDef;

import static org.bytedeco.javacpp.tensorflow.TF_CloseSession;
import static org.bytedeco.javacpp.tensorflow.TF_DeleteSession;
import static org.bytedeco.javacpp.tensorflow.TF_DeleteStatus;
import static org.bytedeco.javacpp.tensorflow.TF_GetCode;
import static org.bytedeco.javacpp.tensorflow.TF_GraphOperationByName;
import static org.bytedeco.javacpp.tensorflow.TF_Message;
import static org.bytedeco.javacpp.tensorflow.TF_NewSessionOptions;
import static org.bytedeco.javacpp.tensorflow.TF_NewStatus;
import static org.bytedeco.javacpp.tensorflow.TF_OK;
import static org.bytedeco.javacpp.tensorflow.TF_SessionRun;
import static org.bytedeco.javacpp.tensorflow.TF_SetConfig;

/**
 * @author Christian Tzolov
 */
public class GrapphRunner2 implements Closeable {

	//the in memory representation parsed from protobuf
	private tensorflow.TF_Graph graph;
	//the conversion between nd4j and tensorflow
	private TensorflowConversion conversion = TensorflowConversion.getInstance();
	//a persistent session to be used when running the graph
	private tensorflow.TF_Session session;
	//the options for the model
	private tensorflow.TF_SessionOptions options;
	//a status object used
	private tensorflow.TF_Status status;

	private List<String> inputOrder, outputOrder;

	private org.tensorflow.framework.ConfigProto protoBufConfigProto;


	/**
	 * Pass in a graph instance and
	 * the length of the protobuf
	 * that it was instantiated with.
	 * For files this is typically
	 * {@link File#length()},
	 * for byte arrays, this is
	 * byte array.length
	 * and for {@link java.nio.ByteBuffer}
	 * this would be something like the
	 * {@link java.nio.ByteBuffer#capacity()}
	 * @param graph a pointer to the {@link tensorflow.TF_Graph} to use when executing
	 * @param graphDef {@link org.tensorflow.framework.GraphDef} protobuf
	 *                                                          definition containing
	 *                                                          the graph configuration
	 *                                                          for automatically inferring
	 *                                                          things like
	 *                                                          graph inputs and outputs
	 */
	public GrapphRunner2(List<String> inputNames, tensorflow.TF_Graph graph, org.tensorflow.framework.GraphDef graphDef) {
		this(inputNames, graph, graphDef, null);

	}

	/**
	 * Pass in a graph instance and
	 * the length of the protobuf
	 * that it was instantiated with.
	 * For files this is typically
	 * {@link File#length()},
	 * for byte arrays, this is
	 * byte array.length
	 * and for {@link java.nio.ByteBuffer}
	 * this would be something like the
	 * {@link java.nio.ByteBuffer#capacity()}
	 * @param graph a pointer to the {@link tensorflow.TF_Graph} to use when executing
	 * @param graphDef {@link org.tensorflow.framework.GraphDef} protobuf
	 *                                                          definition containing
	 *                                                          the graph configuration
	 *                                                          for automatically inferring
	 *                                                          things like
	 *                                                          graph inputs and outputs
	 * @param configProto  the session configuration proto to use with this runner
	 */
	public GrapphRunner2(List<String> inputNames, tensorflow.TF_Graph graph, org.tensorflow.framework.GraphDef graphDef, ConfigProto configProto) {
		this.graph = graph;
		this.protoBufConfigProto = configProto;
		this.inputOrder = inputNames;
		initSessionAndStatusIfNeeded(graphDef);

	}

	/**
	 * Initialize with the graph content to use
	 * @param inputNames the inputs to the graph
	 * @param graphToUse the raw byte content
	 *                   of a protobuf file saved by tensorflow
	 */
	public GrapphRunner2(byte[] graphToUse, List<String> inputNames) {
		this(graphToUse, inputNames, getAlignedWithNd4j());
	}


	/**
	 * Initialize with the graph content to use
	 * @param filePath path of a protobuf file saved by tensorflow
	 * @param inputNames the input namesfor the graph
	 */
	public GrapphRunner2(String filePath, List<String> inputNames) {
		this(filePath, inputNames, getAlignedWithNd4j());
	}


	/**
	 * Initialize with the graph content to use
	 * @param filePath path of a protobuf file saved by tensorflow
	 * @param inputNames the names of the inputs for the graph
	 * @param sessionOptionsConfiguration the session options to use
	 *                                    for running sessions
	 */
	public GrapphRunner2(String filePath, List<String> inputNames, org.tensorflow.framework.ConfigProto sessionOptionsConfiguration) {
		byte[] graphToUse = null;

		try {
			this.inputOrder = inputNames;
			graphToUse = IOUtils.toByteArray(new File(filePath).toURI());
			this.graph = conversion.loadGraph(graphToUse);
			this.protoBufConfigProto = sessionOptionsConfiguration;
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Unable to parse protobuf", e);
		}

		initSessionAndStatusIfNeeded(graphToUse);
	}

	/**
	 * Initialize with the graph content to use
	 * @param graphToUse the raw byte content
	 *                   of a protobuf file saved by tensorflow
	 * @param sessionOptionsConfiguration the session options to use
	 *                                    for running sessions
	 */
	public GrapphRunner2(byte[] graphToUse, List<String> inputNames, org.tensorflow.framework.ConfigProto sessionOptionsConfiguration) {
		try {
			this.graph = conversion.loadGraph(graphToUse);
			this.inputOrder = inputNames;
			this.protoBufConfigProto = sessionOptionsConfiguration;
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Unable to parse protobuf", e);
		}

		initSessionAndStatusIfNeeded(graphToUse);
	}


	/**
	 * Returns a map of the output names
	 * to the ndarrays matching each output.
	 *
	 * Note that {@link IllegalArgumentException}
	 * will be thrown if there are any invalid states such as:
	 * the graph being null
	 *
	 *
	 * the inputs resolved from the graph do not match
	 * the inputs passed in
	 *
	 *
	 *
	 * @param inputs the inputs to use for each
	 *               {@link INDArray}
	 * @return a map of the output names to the
	 * ndarrays matching each output specified in the graph
	 * @throws IOException
	 */
	public Map<String, INDArray> run(Map<String, tensorflow.TF_Tensor> inputs) {
		if (graph == null) {
			throw new IllegalStateException("Graph not initialized.");
		}

		if (inputs.size() != inputOrder.size()) {
			throw new IllegalArgumentException("Number of inputs specified do not match number of arrays specified.");
		}


		Map<String, INDArray> outputArrays = new LinkedHashMap<>();

		Map<String, tensorflow.TF_Operation> opsByName = new HashMap<>();
		tensorflow.TF_Output inputOut = new tensorflow.TF_Output(inputOrder.size());

		tensorflow.TF_Tensor[] inputTensors = new tensorflow.TF_Tensor[inputOrder.size()];
		for (int i = 0; i < inputOrder.size(); i++) {
			tensorflow.TF_Operation inputOp = TF_GraphOperationByName(graph, inputOrder.get(i));
			opsByName.put(inputOrder.get(i), inputOp);
			inputOut.position(i).oper(inputOp).index(0);
			tensorflow.TF_Tensor tf_tensor = inputs.get(inputOrder.get(i));
			inputTensors[i] = tf_tensor;
		}


		//reset the position of the pointer for execution
		inputOut.position(0);

		tensorflow.TF_Output outputOut = new tensorflow.TF_Output(outputOrder.size());
		//only setup the output ops
		for (int i = 0; i < outputOrder.size(); i++) {
			tensorflow.TF_Operation outputOp = TF_GraphOperationByName(graph, outputOrder.get(i));
			opsByName.put(outputOrder.get(i), outputOp);
			outputOut.position(i).oper(outputOp).position(i).index(0);
		}

		//reset the position of the pointer for execution
		outputOut.position(0);


		//these are references to the nd4j ndarrays wrapped for tensorflow
		PointerPointer<tensorflow.TF_Tensor> inputTensorsPointer = new PointerPointer<>(inputTensors);
		//note that these are the result pointers
		//the result pointers are null, and will be populated automatically by the session run
		PointerPointer<tensorflow.TF_Tensor> outputTensorsPointer = new PointerPointer<>(outputOrder.size());


		TF_SessionRun(
				session,
				null,
				//inputs
				inputOut, inputTensorsPointer, inputTensors.length,
				//outputs
				outputOut, outputTensorsPointer, outputOrder.size(),
				//targets
				null, 0,
				null,
				status);


		if (TF_GetCode(status) != TF_OK) {
			throw new RuntimeException("ERROR: Unable to run session " + TF_Message(status).getString());
		}
		else {
			for (int i = 0; i < outputOrder.size(); i++) {
				INDArray to = conversion.ndArrayFromTensor(new tensorflow.TF_Tensor(outputTensorsPointer.get(i)));
				outputArrays.put(outputOrder.get(i), to);
			}

		}

		return outputArrays;
	}


	private void initSessionAndStatusIfNeeded(org.tensorflow.framework.GraphDef graphDef1) {
		outputOrder = new ArrayList<>();
		//infer the inputs and outputs for the graph
		Set<String> seenAsInput = new LinkedHashSet<>();
		for (int i = 0; i < graphDef1.getNodeCount(); i++) {
			NodeDef node = graphDef1.getNode(i);
			for (int input = 0; input < node.getInputCount(); input++) {
				seenAsInput.add(node.getInput(input));
			}
		}
		//find the nodes that were not inputs to any  nodes: these are the outputs
		for (int i = 0; i < graphDef1.getNodeCount(); i++) {
			if (!seenAsInput.contains(graphDef1.getNode(i).getName())) {
				outputOrder.add(graphDef1.getNode(i).getName());
			}
		}


		//setup the status object to be used for all tensorflow calls
		if (status == null) {
			status = TF_NewStatus();
		}


		//setup and configure the session, factoring
		//in the ConfigObject as needed
		if (session == null) {
			options = TF_NewSessionOptions();
			if (protoBufConfigProto != null) {
				BytePointer bytePointer = new BytePointer(protoBufConfigProto.toByteArray());
				TF_SetConfig(options, bytePointer, bytePointer.getStringBytes().length, status);
				if (TF_GetCode(status) != TF_OK) {
					throw new RuntimeException("ERROR: Unable to set value configuration:" + TF_Message(status).getString());
				}
			}

			session = tensorflow.TF_NewSession(graph, options, status);
			if (TF_GetCode(status) != TF_OK) {
				throw new RuntimeException("ERROR: Unable to open session " + TF_Message(status).getString());
			}

		}

	}

	private void initSessionAndStatusIfNeeded(byte[] graphToUse) {
		try {
			//use the protobuf api to load the graph definition and load the node metadata
			org.tensorflow.framework.GraphDef graphDef1 = org.tensorflow.framework.GraphDef.parseFrom(graphToUse);
			initSessionAndStatusIfNeeded(graphDef1);
		}
		catch (InvalidProtocolBufferException e) {
			e.printStackTrace();
		}
	}

	public static org.tensorflow.framework.ConfigProto getAlignedWithNd4j() {
		org.tensorflow.framework.ConfigProto configProto = org.tensorflow.framework.ConfigProto.getDefaultInstance();
		ConfigProto.Builder builder1 = configProto.toBuilder().addDeviceFilters(TensorflowConversion.defaultDeviceForThread());
		try {
			//cuda
			if (Nd4j.getBackend().getClass().getName().toLowerCase().contains("jcu")) {
				builder1.setGpuOptions(GPUOptions.newBuilder()
						.setAllowGrowth(true)
						.setPerProcessGpuMemoryFraction(0.5)
						.build());
			}
			//cpu
			else {
			}

		}
		catch (Exception e) {
			e.printStackTrace();
		}

		return builder1.build();
	}


	/**
	 * Convert a json string written out
	 * by {@link com.github.os72.protobuf351.util.JsonFormat}
	 * to a {@link org.bytedeco.javacpp.tensorflow.ConfigProto}
	 * @param json the json to read
	 * @return the config proto to use
	 */
	public static org.tensorflow.framework.ConfigProto fromJson(String json) {
		org.tensorflow.framework.ConfigProto.Builder builder = org.tensorflow.framework.ConfigProto.newBuilder();
		try {
			JsonFormat.parser().merge(json, builder);
			org.tensorflow.framework.ConfigProto build = builder.build();
			ByteString serialized = build.toByteString();
			byte[] binaryString = serialized.toByteArray();
			org.tensorflow.framework.ConfigProto configProto = org.tensorflow.framework.ConfigProto.parseFrom(binaryString);
			return configProto;
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}


	/**
	 * Write out the session options used
	 * by this {@link GraphRunner}
	 * a s a  json string using the
	 * {@link JsonFormat}
	 * @return
	 */
	public String sessionOptionsToJson() {
		try {
			return JsonFormat.printer().print(protoBufConfigProto);
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}


	@Override
	public void close() {
		if (session != null && status != null) {
			TF_CloseSession(session, status);
			TF_DeleteSession(session, status);
		}

		if (status != null && TF_GetCode(status) != TF_OK) {
			throw new RuntimeException("ERROR: Unable to delete session " + TF_Message(status).getString());
		}


		if (status != null) {
			TF_DeleteStatus(status);
		}
	}
}
