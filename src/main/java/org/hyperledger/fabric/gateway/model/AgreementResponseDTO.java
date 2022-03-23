package org.hyperledger.fabric.gateway.model;

import org.hyperledger.fabric.sdk.ProposalResponse;

import java.util.ArrayList;
import java.util.Collection;

public class AgreementResponseDTO {
  private Collection<ProposalResponse> responses;

  private boolean agreementReached;

  public AgreementResponseDTO() {
    responses = new ArrayList<>();

    agreementReached = false;
  }

  public Collection<ProposalResponse> getResponses() {
    return responses;
  }

  public void setAgreementReached(boolean agreementReached) {
    this.agreementReached = agreementReached;
  }

  public void setResponses(Collection<ProposalResponse> responses) {
    this.responses = responses;
  }

  public boolean isAgreementReached() {
    return agreementReached;
  }
}
