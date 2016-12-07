package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseFeed.java
//
//   Convert the data read from the http feed into Json
//**********************************************************************
//**********************************************************************

// E.g. parse the data returned from Cambridge local parking occupancy API

// Polls https://www.cambridge.go.uk/jdi_parking_ajax/complete
// Gets (without these added linebreaks):
/*
<h2><a href="/grafton-east-car-park">Grafton East car park</a></h2><p><strong>384 spaces</strong> (51% full and filling)</p>
<h2><a href="/grafton-west-car-park">Grafton West car park</a></h2><p><strong>98 spaces</strong> (65% full and filling)</p>
<h2><a href="/grand-arcade-car-park">Grand Arcade car park</a></h2><p><strong>40 spaces</strong> (96% full and filling)</p>
<h2><a href="/park-street-car-park">Park Street car park</a></h2><p><strong>152 spaces</strong> (59% full and filling)</p>
<h2><a href="/queen-anne-terrace-car-park">Queen Anne Terrace car park</a></h2><p><strong>1 spaces</strong> (100% full and emptying)</p>
*/

// Returns:
/*
{
   "module_name": "feedmaker",                  // as given to the FeedMaker in config, typically "feedmaker"
   "module_id":   "cam_parking_local",          // from config, but platform unique value within module_name
   "msg_type":    "car_parking",                // Constants.FEED_CAR_PARKING
   "feed_id":     "cam_parking_local",          // identifies http source, matches config
   "filename":    "1459762951_2016-04-04-10-42-31",
   "filepath":    "2016/04/04",
   "request_data":[                             // actual parsed data from source, in this case car park occupancy
                    { "area_id":         "cam",
                      "parking_id":      "grafton_east",
                      "parking_name":    "Grafton East",
                      "spaces_capacity": 874,
                      "spaces_free":     384,
                      "spaces_occupied": 490
                    } ...
                   ]
}

*/
// So the basic idea is that ParseFeed is passed a general (typically human-readable) page from which it
// extracts an ARRAY of records of parsed data.
// The source page (typically a web page) may contain, for example, the occupancy of various car parks.
//
// ParseFeed uses 'FeedTemplates' that specify where the data is in the received page, in the form of:
//  * a 'start_tag' (i.e. text string) which indicates the start of the corresponding data record on the page
//  * a list of 'field' definitions (FieldTemplates) which show where the required field data is on the page
//      immediately following the 'start_tag'.
//  * a field definition can also provide a hard-coded value for a field to be returned.
// ParseFeed will iterate through the defined FeedTemplates picking out the required data as instructed by each.
// Each FeedTemplate results in a JsonObject, which are accumulated in a JsonArray that is the returned result.
//

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;


public class ParseFeed {

    private String feed_type; // e.g. "cam_park_local"

    private String area_id;

    private Log logger;

    // structure holding templates for each feed type
    // i.e. feed_templates["cam_park_local"] gives templates for that feed type
    HashMap<String, ArrayList<FeedTemplate>> feed_templates;
    
    ParseFeed(String feed_type, String area_id, Log logger)
        {
           this.feed_type = feed_type;

           this.area_id = area_id;

           this.logger = logger;

           feed_templates = init_templates();
           
           logger.log(Constants.LOG_DEBUG, "ParseFeed started for "+feed_type);
        }

    // Here is where we try and parse the page and return a JsonArray
    public JsonArray parse_array(String page)
        {

            logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array called with page");

            JsonArray records = new JsonArray();

            // try and match each known car park to the data
            for (int i=0; i<feed_templates.get(feed_type).size(); i++)
                {
                    FeedTemplate feed_template = feed_templates.get(feed_type).get(i);
                      
                    logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array trying template "+feed_template.tag_start);

                    // ...grafton-east-car-park...<strong>384 spaces...
                    int rec_start = page.indexOf(feed_template.tag_start); // find start of record 
                    if (rec_start < 0) continue;  // if not found then skip current feed_template

                    JsonObject json_record = new JsonObject();

                    for (int j=0; j<feed_template.fields.size(); j++)
                    {
                        FieldTemplate field_template = feed_template.fields.get(j);
                           
                        //logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array trying field "+field_template.field_name);

                        // if field value already in field template, just use that
                        if (field_template.field_type == "fixed_int")
                        {
                          json_record.put(field_template.field_name, field_template.fixed_int);
                          //logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array "+
                          //                                field_template.field_name+" fixed_int = "+
                          //                                field_template.fixed_int
                          //          );
                          continue;
                        }
                        else if (field_template.field_type == "fixed_string")
                        {
                          json_record.put(field_template.field_name, field_template.fixed_string);
                          //logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array "+
                          //                                field_template.field_name+" fixed_string = "+
                          //                                field_template.fixed_string
                          //          );
                          continue;
                        }

                        else if (field_template.field_type == "calc_minus")
                        {
                            int v1 = json_record.getInteger(field_template.s1);
                            int v2 = json_record.getInteger(field_template.s2);
                            json_record.put(field_template.field_name, v1-v2);
                            continue;
                        }
                        else if (field_template.field_type == "calc_plus")
                        {
                            int v1 = json_record.getInteger(field_template.s1);
                            int v2 = json_record.getInteger(field_template.s2);
                            json_record.put(field_template.field_name, v1+v2);
                            continue;
                        }

                        // field value was not in template, so parse from page

                        // find index of start of field, or skip this feed_template
                        int field_start = page.indexOf(field_template.s1, rec_start);
                        if (field_start < 0) continue;
                        field_start = field_start + field_template.s1.length();

                        // find index of end of field, or skip this feed_template
                        int field_end = page.indexOf(field_template.s2, field_start);
                        if (field_end < 0) continue;
                        if (field_end - field_start > 40) continue;

                        // pick out the field value, or skip if not recognized
                        String field_string = page.substring(field_start, field_end);
                        if (field_template.field_type == "int")
                        {
                            try {
                                int int_value = Integer.parseInt(field_string);
                                json_record.put(field_template.field_name, int_value);
                            } catch (NumberFormatException e) {
                                continue;
                            }
                            continue;
                        }
                        else if (field_template.field_type == "string")
                        {
                            json_record.put(field_template.field_name, field_string);
                            continue;
                        }
                        //json_record.put("area_id",area_id);
                        //int spaces_occupied = parking.capacity - spaces_free;
                        //if (spaces_occupied < 0) spaces_occupied = 0;
                        //json_record.put("spaces_occupied", spaces_occupied);
                    }

                    logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array found "+json_record);

                    records.add(json_record);
                }

            return records;
        }
    
    // get current local time as "YYYY-MM-DD hh:mm:ss"
    public static String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    class FeedTemplate {
        public String tag_start;
        public ArrayList<FieldTemplate> fields;
    }

    // this is a 'template' for a field to be extracted from the feed
    class FieldTemplate {
        public String field_name;  // the JSON name to be given to the value
        public String field_type;  // int | string | fixed_int | fixed_string
        public String fixed_string; // if field_type = "fixed_string" then this will be required fixed value
        public int    fixed_int;    // as above but for int value
        public String s1; // s1,s2 are multi-purpose strings:
        public String s2; // (i) start and end tags of required fields
                          // (ii) the names of two existing json fields for field_type="calc*" (e.g. calc_minus)

        FieldTemplate(String field_name, String field_type, String fixed_string, int fixed_int,
                      String s1, String s2)
        {
            this.field_name = field_name;
            this.field_type = field_type;
            this.fixed_string = fixed_string;
            this.fixed_int = fixed_int;
            this.s1 = s1;
            this.s2 = s2;
        }
    }

    // initialise the templates structure
    //debug - this feed template config data is hardcoded into this program, will be moved out into
    // config file or database, with feed_type used to look it up.
    HashMap<String,ArrayList<FeedTemplate>> init_templates()
    {
       HashMap<String,ArrayList<FeedTemplate>> feed_templates = new HashMap<String,ArrayList<FeedTemplate>>();

       // ********************************************************************************
       // **************  cam_park_local feed template    ********************************
       // ********************************************************************************
       ArrayList<FeedTemplate> cam_park_local = new ArrayList<FeedTemplate>();

       FeedTemplate ft;

       // Grafton East Car Park
       ft = new FeedTemplate();
       ft.tag_start = "grafton-east-car-park";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grafton-east-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,780,null,null));
       ft.fields.add(new FieldTemplate("spaces_free","int",null,0,"<strong>"," spaces"));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free"));
       cam_park_local.add(ft);

       // Grafton West Car Park
       ft = new FeedTemplate();
       ft.tag_start = "grafton-west-car-park";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grafton-west-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,280,null,null));
       ft.fields.add(new FieldTemplate("spaces_free","int",null,0,"<strong>"," spaces"));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free"));
       cam_park_local.add(ft);

       // Grand Arcade Car Park
       ft = new FeedTemplate();
       ft.tag_start = "grand-arcade-car-park";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grand-arcade-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,890,null,null));
       ft.fields.add(new FieldTemplate("spaces_free","int",null,0,"<strong>"," spaces"));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free"));
       cam_park_local.add(ft);

       // Park Street Car Park
       ft = new FeedTemplate();
       ft.tag_start = "park-street-car-park";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","park-street-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,375,null,null));
       ft.fields.add(new FieldTemplate("spaces_free","int",null,0,"<strong>"," spaces"));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free"));
       cam_park_local.add(ft);

       // Queen Anne Terrace Car Park
       ft = new FeedTemplate();
       ft.tag_start = "queen-anne-terrace-car-park";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","queen-anne-terrace-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,540,null,null));
       ft.fields.add(new FieldTemplate("spaces_free","int",null,0,"<strong>"," spaces"));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free"));
       cam_park_local.add(ft);

       feed_templates.put("cam_park_local", cam_park_local);

       // ********************************************************************************
       // **************  cam_park_rss feed template    ********************************
       // ********************************************************************************
       ArrayList<FeedTemplate> cam_park_rss = new ArrayList<FeedTemplate>();

       // Grand Arcade from cam_park_rss
       ft = new FeedTemplate();
       ft.tag_start = "Grand Arcade";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grand-arcade-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // Grafton East Car Park
       ft = new FeedTemplate();
       ft.tag_start = "Grafton East";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grafton-east-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // Grafton West Car Park
       ft = new FeedTemplate();
       ft.tag_start = "Grafton West";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grafton-west-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // Park Street Car Park
       ft = new FeedTemplate();
       ft.tag_start = "Park Street";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","park-street-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // Queen Anne Terrace Car Park
       ft = new FeedTemplate();
       ft.tag_start = "Queen Anne";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","queen-anne-terrace-car-park",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // P&R Madingley Road
       ft = new FeedTemplate();
       ft.tag_start = "Madingley Road";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","madingley-road-park-and-ride",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // P&R Trumpington
       ft = new FeedTemplate();
       ft.tag_start = "Trumpington";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","trumpington-park-and-ride",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // P&R Babraham
       ft = new FeedTemplate();
       ft.tag_start = "Babraham";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","babraham-park-and-ride",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // P&R Milton
       ft = new FeedTemplate();
       ft.tag_start = "Milton";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","milton-park-and-ride",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // P&R Newmarket Road Front
       ft = new FeedTemplate();
       ft.tag_start = "Newmarket Rd Front";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","newmarket-road-front-park-and-ride",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);

       // P&R Newmarket Road Rear
       ft = new FeedTemplate();
       ft.tag_start = "Newmarket Rd Rear";
       ft.fields = new ArrayList<FieldTemplate>();
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","newmarket-road-rear-park-and-ride",0,null,null));
       ft.fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity"));
       ft.fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken "));
       ft.fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied"));
       cam_park_rss.add(ft);


       feed_templates.put("cam_park_rss", cam_park_rss);


       return feed_templates;
    }       
}