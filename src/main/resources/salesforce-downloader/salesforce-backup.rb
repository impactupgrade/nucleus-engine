#!/usr/bin/ruby

require 'net/http'
require 'net/https'
require 'rexml/document'
require 'date'
require 'net/smtp'
require 'yaml'
require 'fileutils'

include REXML

class Result
  def initialize(xmldoc)
    @xmldoc = xmldoc
  end

  def server_url
    @server_url ||= XPath.first(@xmldoc, '//result/serverUrl/text()')
  end

  def session_id
    @session_id ||= XPath.first(@xmldoc, '//result/sessionId/text()')
  end

  def org_id
    @org_id ||= XPath.first(@xmldoc, '//result/userInfo/organizationId/text()')
  end
end

class SfError < Exception
  attr_accessor :resp

  def initialize(resp)
    @resp = resp
  end

  def inspect
    puts resp.body
  end
  alias_method :to_s, :inspect
end

### Helpers ###

def http(host=ENV["SFDC_URL"], port=443)
    h = Net::HTTP.new(host, port)
    h.use_ssl = true
    h
end

def headers(login)
  {
    'Cookie'         => "oid=#{login.org_id.value}; sid=#{login.session_id.value}",
    'X-SFDC-Session' => login.session_id.value
  }
end

def file_name(url=nil)
  datestamp = Date::today.strftime('%Y-%m-%d')
  uid_string = url ? "-#{/.*fileName=(.*)\.ZIP.*/.match(url)[1]}" : ''
  "salesforce-#{datestamp}#{uid_string}.ZIP"
end

def progress_percentage(current, total)
  ((current.to_f/total.to_f)*(100.to_f)).to_i
end

### Salesforce interactions ###

def login
  puts "Logging in..."
  path = '/services/Soap/u/48.0'

  pwd_token_encoded = ENV["SFDC_PASSWORD"]
  pwd_token_encoded = pwd_token_encoded.gsub(/&(?!amp;)/,'&amp;')
  pwd_token_encoded = pwd_token_encoded.gsub(/</,'&lt;')
  pwd_token_encoded = pwd_token_encoded.gsub(/>/,'&gt;')

  inital_data = <<-EOF
<?xml version="1.0" encoding="utf-8" ?>
<env:Envelope xmlns:xsd="http://www.w3.org/2001/XMLSchema"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:env="http://schemas.xmlsoap.org/soap/envelope/">
  <env:Body>
    <n1:login xmlns:n1="urn:partner.soap.sforce.com">
      <n1:username>#{ENV["SFDC_USERNAME"]}</n1:username>
      <n1:password>#{pwd_token_encoded}</n1:password>
    </n1:login>
  </env:Body>
</env:Envelope>
  EOF

  puts inital_data

  initial_headers = {
    'Content-Type' => 'text/xml; charset=UTF-8',
    'SOAPAction' => 'login'
  }

  resp = http('login.salesforce.com').post(path, inital_data, initial_headers)

  if resp.code == '200'
    xmldoc = Document.new(resp.body)
    return Result.new(xmldoc)
  else
    raise SfError.new(resp)
  end
end

def download_index(login)
  puts "Downloading index..."
  path = '/servlet/servlet.OrgExport'
  data = http.post(path, nil, headers(login))
  data.body.strip
end

def get_download_size(login, url)
  puts "Getting download size..."
  data = http.head(url, headers(login))
  data['Content-Length'].to_i
end

def download_file(login, url, expected_size)
  printing_interval = 10
  interval_type = :percentage
  last_printed_value = nil
  size = 0
  fn = file_name(url)
  puts "Downloading #{fn}..."
  f = open("backup-salesforce/#{fn}", "wb")
  begin
    http.request_get(url, headers(login)) do |resp|
      resp.read_body do |segment|
        f.write(segment)
        size = size + segment.size
        last_printed_value = print_progress(size, expected_size, printing_interval, last_printed_value, interval_type)
      end
      puts "\nFinished downloading #{fn}!"
    end
  ensure
    f.close()
  end
  raise "Size didn't match. Expected: #{expected_size} Actual: #{size}" unless size == expected_size
end

def print_progress(size, expected_size, interval, previous_printed_interval, interval_type=:seconds)
  percent_file_complete = ((size.to_f/expected_size.to_f)*(100.to_f)).to_i
  case interval_type
    when :percentage
    previous_printed_interval ||= 0
    current_value = percent_file_complete
    when :seconds
    previous_printed_interval ||= Time.now.to_i
    current_value = Time.now.to_i
  end
  next_interval = previous_printed_interval + interval
  if current_value >= next_interval
    timestamp = Time.now.strftime('%Y-%m-%d-%H-%M-%S')
    puts "#{timestamp}: #{percent_file_complete}% complete (#{size} of #{expected_size})"
    return next_interval
  end
  return previous_printed_interval
end

begin
  result = login
  urls = download_index(result).split("\n")
  puts "  All urls:"
  puts urls
  puts ''

  unless File.directory?("backup-salesforce")
    FileUtils.mkdir_p("backup-salesforce")
  end

  urls.each do |url|
    fn = file_name(url)
    file_path = "backup-salesforce/#{fn}"
    retry_count = 0
    begin
      puts "Working on: #{url}"
      expected_size = get_download_size(result, url)
      puts "Expected size: #{expected_size}"
      fs = File.size?(file_path)
      if fs && fs == expected_size
        puts "File #{fn} exists and is the right size. Skipping."
      else
        download_file(result, url, expected_size)
      end
    rescue Exception => e
      if retry_count < 5
        retry_count += 1
        puts "Error: #{e}"
        puts "Retrying (retry_count of 5)..."
        retry
      end
    end
  end
  puts "Done!"
end




