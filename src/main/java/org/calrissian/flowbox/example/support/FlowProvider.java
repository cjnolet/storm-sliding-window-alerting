package org.calrissian.flowbox.example.support;


import org.calrissian.flowbox.model.Flow;

import java.io.Serializable;
import java.util.List;

public interface FlowProvider extends Serializable {

  List<Flow> getFlows();
}
