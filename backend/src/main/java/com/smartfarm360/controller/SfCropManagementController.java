package com.smartfarm360.controller;
import com.smartfarm360.service.SfCropManagementService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/smartfarm/tenants/{tenantId}")
public class SfCropManagementController { private final SfCropManagementService service; public SfCropManagementController(SfCropManagementService service){this.service=service;}
 @GetMapping("/crops") public List<Map<String,Object>> crops(Authentication a,@PathVariable Long tenantId){return service.crops(a.getName(),tenantId);}
 @PostMapping("/crops") public Map<String,Object> crop(Authentication a,@PathVariable Long tenantId,@RequestBody Map<String,Object>b){return service.createCrop(a.getName(),tenantId,b);}
 @GetMapping("/crops/{cropId}/varieties") public List<Map<String,Object>> varieties(Authentication a,@PathVariable Long tenantId,@PathVariable Long cropId){return service.varieties(a.getName(),tenantId,cropId);}
 @PostMapping("/crops/{cropId}/varieties") public Map<String,Object> variety(Authentication a,@PathVariable Long tenantId,@PathVariable Long cropId,@RequestBody Map<String,Object>b){return service.createVariety(a.getName(),tenantId,cropId,b);}
 @GetMapping("/seasons") public List<Map<String,Object>> seasons(Authentication a,@PathVariable Long tenantId){return service.seasons(a.getName(),tenantId);}
 @PostMapping("/seasons") public Map<String,Object> season(Authentication a,@PathVariable Long tenantId,@RequestBody Map<String,Object>b){return service.createSeason(a.getName(),tenantId,b);}
 @GetMapping("/cultivation-cycles") public List<Map<String,Object>> cycles(Authentication a,@PathVariable Long tenantId,@RequestParam(required=false) Long farmId){return service.cycles(a.getName(),tenantId,farmId);}
 @PostMapping("/cultivation-cycles") public Map<String,Object> cycle(Authentication a,@PathVariable Long tenantId,@RequestBody Map<String,Object>b){return service.createCycle(a.getName(),tenantId,b);}
 @GetMapping("/activities") public List<Map<String,Object>> activities(Authentication a,@PathVariable Long tenantId,@RequestParam(required=false) Long cycleId){return service.activities(a.getName(),tenantId,cycleId);}
 @PostMapping("/activities") public Map<String,Object> activity(Authentication a,@PathVariable Long tenantId,@RequestBody Map<String,Object>b){return service.createActivity(a.getName(),tenantId,b);}
 @GetMapping("/harvests") public List<Map<String,Object>> harvests(Authentication a,@PathVariable Long tenantId,@RequestParam(required=false) Long cycleId){return service.harvests(a.getName(),tenantId,cycleId);}
 @PostMapping("/harvests") public Map<String,Object> harvest(Authentication a,@PathVariable Long tenantId,@RequestBody Map<String,Object>b){return service.createHarvest(a.getName(),tenantId,b);}
}
