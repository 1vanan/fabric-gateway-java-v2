package org.hyperledger.fabric.gateway.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.IntStream;

public class SendingConfirmationServiceImpl {
    private List<String> cashedOrganizations;
    private TransactionProposalRequest request = null;
    private ProposalResponse correctResponse = null;
    private Collection<Peer> endorsingPeers = null;

    private final int MAX_REQUEST_NUM = 10;
    private final int MAX_REQUEST_TOTAL_NUM = 30;

    private final Logger logger = Logger.getLogger("SchedulerLog");
    private FileHandler fh;

    public Collection<ProposalResponse> sendForConfirmationCustomId(final Channel channel, final String analyticsPath, final int modelId,
                                                                    final ProposalResponse correctResponse, final TransactionProposalRequest request,
                                                                    final Collection<Peer> endorsingPeers) throws InvalidArgumentException, ProposalException {
        ObjectMapper objectMapper = new ObjectMapper();
        this.request = request;
        this.correctResponse = correctResponse;
        this.endorsingPeers = endorsingPeers;

        try {
            ModelCheckerResultWrapper modelCheckerResultWrapper = objectMapper.readValue(new File(analyticsPath),
                    ModelCheckerResultWrapper.class);

            ModelCheckResult result = null;

            for (ModelCheckResult r : modelCheckerResultWrapper.getModelCheckResultList()) {
                if (r.getId() == modelId) {
                    result = r;
                    break;
                }
            }

            if (result == null) {
                throw new IllegalArgumentException(String.format("Could not find model with the given id: %d", modelId));
            }

            return sendForConfirmation(channel, result, modelCheckerResultWrapper);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // in case of error
        return null;
    }

    private Collection<ProposalResponse> sendForConfirmation(final Channel channel, final ModelCheckResult result,
                                                             final ModelCheckerResultWrapper modelCheckerResultWrapper)
            throws IOException, InvalidArgumentException, ProposalException {
        fh = new FileHandler("./result");
        logger.addHandler(fh);
        logger.setUseParentHandlers(false);
        SimpleFormatter formatter = new SimpleFormatter();
        fh.setFormatter(formatter);

        this.cashedOrganizations = modelCheckerResultWrapper.getOrganizations();
        int[] responses = IntStream.generate(() -> -1)
                .limit(cashedOrganizations.size())
                .toArray();

        Set<int[]> backwards = result.getBackwardTransitions();
        Set<int[]> specs = modelCheckerResultWrapper.getSpecification();

        Map<String, Integer> numberOfRequestsPerOrganization = new HashMap<>();
        cashedOrganizations.forEach(o -> numberOfRequestsPerOrganization.put(o, 0));

        return sendForConfirmationToOrganization(channel, cashedOrganizations, responses, 0,
                0, specs, backwards, numberOfRequestsPerOrganization, new ArrayList<>());
    }

    private Collection<ProposalResponse> sendForConfirmationToOrganization(final Channel channel, final List<String> organizations,
                                                                           final int[] responses, final int responseIndex, final int messagesCnt,
                                                                           final Set<int[]> spec, final Set<int[]> backwards,
                                                                           final Map<String, Integer> numberOfRequestsPerOrganization,
                                                                           final Collection<ProposalResponse> resultResponses)
            throws InvalidArgumentException, ProposalException {
        String orgToSend = organizations.get(0);
        Peer peerToSend = endorsingPeers.stream().filter(e -> e.getName().equals(orgToSend)).findAny().get();

        int requestsNumToOrg = numberOfRequestsPerOrganization.get(orgToSend);

        numberOfRequestsPerOrganization.put(orgToSend, requestsNumToOrg + 1);
        Collection<ProposalResponse> proposalResponses = channel.sendTransactionProposal(request, Collections.singleton(peerToSend));


        int reply = Arrays.equals(proposalResponses.iterator().next().getChaincodeActionResponsePayload(),
                correctResponse.getChaincodeActionResponsePayload()) ? 1 : 0;

        if (reply == 1) {
            resultResponses.add(proposalResponses.iterator().next());
        }

        logger.info(String.format("Send for confirmation to %s finished with response %b", orgToSend, reply == 1));

        responses[responseIndex] = reply;

        Optional<int[]> specOpt = spec.stream().filter(s -> Arrays.equals(responses, s)).findAny();
        Optional<int[]> backwardsOpt = backwards.stream().filter(b -> Arrays.equals(responses, b)).findAny();

        if (backwardsOpt.isPresent()) {
            if (requestsNumToOrg >= MAX_REQUEST_NUM) {
                logger.warning(String.format("Organization %s has reached the max amount of requests. " +
                        "Remove backward transition for this organization.", orgToSend));
                backwards.remove(responses);
            }

            backwardsOpt = backwards.stream().filter(b -> Arrays.equals(responses, b)).findAny();
        }

        if (specOpt.isPresent()) {
            logger.info(String.format("Consensus is reached with %d messages", messagesCnt + 1));
            return resultResponses;
        } else if (messagesCnt == MAX_REQUEST_TOTAL_NUM) {
            logger.warning("Max number of messages was sent. Consensus is not reached.");
            return resultResponses;
        } else if (backwardsOpt.isPresent() && reply != 1) {
            int[] initialResponses = IntStream.generate(() -> -1)
                    .limit(cashedOrganizations.size())
                    .toArray();

            logger.info(String.format("Make backward transition from organization %s", orgToSend));

            return sendForConfirmationToOrganization(channel, this.cashedOrganizations, initialResponses, 0,
                    messagesCnt + 1, spec, backwards, numberOfRequestsPerOrganization, resultResponses);
        } else if (IntStream.of(responses).noneMatch(x -> x == -1)) {
            // reached the end of the tree
            return resultResponses;
        } else {
            organizations.remove(orgToSend);
            return sendForConfirmationToOrganization(channel, organizations, responses, responseIndex + 1,
                    messagesCnt + 1, spec, backwards, numberOfRequestsPerOrganization, resultResponses);
        }
    }

    public static class ModelCheckerResultWrapper {
        List<ModelCheckResult> modelCheckResultList;
        Set<int[]> specification;
        List<String> organizations;

        public ModelCheckerResultWrapper() {
        }

        public Set<int[]> getSpecification() {
            return specification;
        }

        public List<ModelCheckResult> getModelCheckResultList() {
            return modelCheckResultList;
        }

        public void setModelCheckResultList(List<ModelCheckResult> modelCheckResultList) {
            this.modelCheckResultList = modelCheckResultList;
        }

        public void setSpecification(Set<int[]> specification) {
            this.specification = specification;
        }

        public List<String> getOrganizations() {
            return organizations;
        }

        public void setOrganizations(List<String> organizations) {
            this.organizations = organizations;
        }
    }

    public static class ModelCheckResult {
        List<String> organizations;
        private Set<int[]> backwardTransitions;
        private double probability;
        private double expectedMessages;
        private int id;
        private long epochTimestamp;

        public ModelCheckResult() {

        }

        public ModelCheckResult(List<String> organizations) {
            this.organizations = organizations;
        }

        public double getExpectedMessages() {
            return expectedMessages;
        }

        public double getProbability() {
            return probability;
        }

        public Set<int[]> getBackwardTransitions() {
            return backwardTransitions;
        }

        public int getId() {
            return id;
        }

        public long getEpochTimestamp() {
            return epochTimestamp;
        }

        public void setBackwardTransitions(Set<int[]> backwardTransitions) {
            this.backwardTransitions = backwardTransitions;
        }

        public void setExpectedMessages(double expectedMessages) {
            this.expectedMessages = expectedMessages;
        }

        public void setProbability(double probability) {
            this.probability = probability;
        }

        public void setId(int id) {
            this.id = id;
        }

        public void setEpochTimestamp(long epochTimestamp) {
            this.epochTimestamp = epochTimestamp;
        }

        @Override
        public String toString() {
            StringBuilder backwards = new StringBuilder();
            backwards.append("[");
            backwardTransitions.forEach(bl -> {
                backwards.append("{");
                Arrays.stream(bl).forEach(b -> {
                    backwards.append(b);
                    backwards.append(" ");
                });
                backwards.append("} ");
            });
            backwards.append("]");
            return "ModelCheckResult{" +
                    "backwardTransitions=" + backwards +
                    ", probability=" + probability +
                    ", expectedMessages=" + expectedMessages +
                    '}';
        }
    }
}

