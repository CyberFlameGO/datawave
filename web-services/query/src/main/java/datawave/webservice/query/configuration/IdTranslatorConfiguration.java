package datawave.webservice.query.configuration;

import datawave.microservice.query.QueryParameters;
import datawave.query.data.UUIDType;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.springframework.stereotype.Component;

import javax.ws.rs.core.MultivaluedMap;
import java.util.ArrayList;
import java.util.List;

@Component("idTranslatorConfiguration")
public class IdTranslatorConfiguration {
    
    private List<UUIDType> uuidTypes = null;
    private String columnVisibility = null;
    private String beginDate = null;
    
    public String getBeginDate() {
        return this.beginDate;
    }
    
    public String getColumnVisibility() {
        return this.columnVisibility;
    }
    
    public List<UUIDType> getUuidTypes() {
        return this.uuidTypes;
    }
    
    public void setBeginDate(String beginDate) {
        this.beginDate = beginDate;
    }
    
    public void setColumnVisibility(String columnVisibility) {
        this.columnVisibility = columnVisibility;
    }
    
    public void setUuidTypes(List<UUIDType> uuidTypes) {
        List<UUIDType> goodTypes = new ArrayList<>();
        if (uuidTypes != null) {
            for (UUIDType uuidType : uuidTypes) {
                if (uuidType.getDefinedView().equalsIgnoreCase("LuceneUUIDEventQuery")) {
                    goodTypes.add(uuidType);
                }
            }
        }
        this.uuidTypes = goodTypes;
    }
    
    public MultivaluedMap<String,String> optionalParamsToMap() {
        MultivaluedMap<String,String> p = new MultivaluedMapImpl<>();
        if (this.columnVisibility != null) {
            p.putSingle(QueryParameters.QUERY_VISIBILITY, this.columnVisibility);
        }
        return p;
    }
}